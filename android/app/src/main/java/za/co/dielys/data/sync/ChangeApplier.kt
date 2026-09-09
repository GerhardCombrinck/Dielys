package za.co.dielys.data.sync

import androidx.room.withTransaction
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.ListEntity
import za.co.dielys.data.local.SyncStateEntity
import za.co.dielys.data.local.TaskEntity
import za.co.dielys.data.remote.ChangeEnvelope
import za.co.dielys.data.remote.ListChange
import za.co.dielys.data.remote.Task
import za.co.dielys.data.remote.TaskChange
import za.co.dielys.data.remote.TaskList
import javax.inject.Inject
import javax.inject.Singleton

/** What [ChangeApplier.apply] did with a change. */
enum class ApplyOutcome {
    /** Written, and the cursor advanced past it. */
    APPLIED,

    /** Already at or past this seq. Redelivery is a no-op, not an error (F5.2). */
    ALREADY_APPLIED,

    /**
     * A seq beyond `cursor + 1`. Nothing was written and the cursor did not move:
     * the caller MUST pull the missing range and never skip ahead (F5.6).
     */
    GAP,
}

/**
 * The single place a change becomes local state.
 *
 * A change received over the WebSocket and a change received from a `?since=`
 * catch-up both come through here. Two code paths would be how a gap gets missed.
 *
 * The write and the cursor advance are one transaction, which is what F5.8 means
 * in practice: a crash between "received" and "written" replays the same change on
 * next launch rather than skipping it. There is no code path that advances the
 * cursor on receipt.
 */
@Singleton
class ChangeApplier
    @Inject
    constructor(
        private val db: DielysDatabase,
    ) {
        /**
         * `withTransaction` is reentrant, so callers that are already inside one —
         * the drain, which deletes the outbox row in the same breath — get a single
         * atomic unit rather than two.
         */
        suspend fun apply(change: ChangeEnvelope): ApplyOutcome =
            db.withTransaction {
                val state = db.syncState().find(change.listId)
                val cursor = state?.cursor ?: 0L

                when {
                    change.seq <= cursor -> ApplyOutcome.ALREADY_APPLIED
                    change.seq != cursor + 1 -> ApplyOutcome.GAP
                    else -> {
                        write(change)
                        db.syncState().upsert(
                            SyncStateEntity(
                                listId = change.listId,
                                cursor = change.seq,
                                serverMaxSeq = maxOf(state?.serverMaxSeq ?: 0L, change.seq),
                            ),
                        )
                        ApplyOutcome.APPLIED
                    }
                }
            }

        /** Records what the server says the head is, without moving the cursor. */
        suspend fun noteServerMaxSeq(
            listId: String,
            maxSeq: Long,
        ) {
            db.withTransaction {
                val state = db.syncState().find(listId)
                db.syncState().upsert(
                    SyncStateEntity(
                        listId = listId,
                        cursor = state?.cursor ?: 0L,
                        serverMaxSeq = maxOf(state?.serverMaxSeq ?: 0L, maxSeq),
                    ),
                )
            }
        }

        /**
         * The envelope carries the whole entity as the server resolved it, so there
         * is no per-field merge to do here — F5.4 happens server-side, and applying
         * in seq order (F5.6) is what makes the last write the right one.
         */
        private suspend fun write(change: ChangeEnvelope) {
            when (change) {
                is TaskChange -> db.tasks().upsert(change.entity.toEntity())
                is ListChange -> {
                    // `role` is local, from /auth/memberships, and is not part of
                    // the changelog. Keep whatever is already known.
                    val existing = db.lists().find(change.entity.id)
                    db.lists().upsert(change.entity.toEntity(existing?.role))
                }
            }
        }
    }

internal fun Task.toEntity(): TaskEntity =
    TaskEntity(
        id = id,
        listId = listId,
        title = title,
        done = done,
        starred = starred,
        position = position,
        deletedAt = deletedAt,
        updatedAt = updatedAt,
    )

internal fun TaskList.toEntity(role: String?): ListEntity =
    ListEntity(
        id = id,
        title = title,
        backgroundPhotoUrl = backgroundPhotoUrl,
        deletedAt = deletedAt,
        updatedAt = updatedAt,
        role = role,
    )
