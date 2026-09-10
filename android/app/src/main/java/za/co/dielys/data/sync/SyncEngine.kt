package za.co.dielys.data.sync

import androidx.room.withTransaction
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.ListEntity
import za.co.dielys.data.local.OutboxEntity
import za.co.dielys.data.local.PushTokenStore
import za.co.dielys.data.remote.ApiException
import za.co.dielys.data.remote.DielysJson
import za.co.dielys.data.remote.SetListPositionRequest
import za.co.dielys.data.remote.SyncApi
import javax.inject.Inject
import javax.inject.Singleton

/** Why a sync run stopped. */
sealed interface SyncOutcome {
    /** The outbox is empty and every known list is caught up. */
    data object Success : SyncOutcome

    /** The server or the network was unavailable. Try again with backoff. */
    data class Retry(
        val reason: ApiException,
    ) : SyncOutcome

    /**
     * The refresh token no longer works. Backing off forever against the auth
     * endpoint helps nobody; a fresh login re-enqueues the work.
     */
    data class SessionExpired(
        val reason: ApiException,
    ) : SyncOutcome
}

/**
 * Drains the outbox, then pulls whatever the server has that this device does not.
 *
 * Transport-agnostic on purpose: it talks to [SyncApi], so every H3 offline
 * scenario is a JVM test against a fake that throws, with no emulator and no
 * socket (H1).
 */
@Singleton
class SyncEngine
    @Inject
    constructor(
        private val db: DielysDatabase,
        private val api: SyncApi,
        private val applier: ChangeApplier,
        private val push: PushTokenStore,
    ) {
        suspend fun sync(): SyncOutcome {
            val drained = drainOutbox()
            if (drained != SyncOutcome.Success) return drained
            val discovered = discoverLists()
            if (discovered != SyncOutcome.Success) return discovered
            val caught = catchUpAll()
            if (caught != SyncOutcome.Success) return caught
            return registerPushToken()
        }

        /**
         * Tells the server the FCM token this install holds, when it does not
         * already have it (M2).
         *
         * Last, and never in front of the data path: a device that cannot register
         * its token is late to hear about changes, while a device that cannot drain
         * its outbox is holding edits nobody else can see. `WorkManager` retries
         * the whole run, and every step in it is idempotent.
         *
         * The comparison is against what the server has confirmed, not against what
         * FCM last said, so a registration lost to a dead spot is sent again on the
         * next run rather than assumed to have landed.
         */
        suspend fun registerPushToken(): SyncOutcome {
            val token = push.pushToken ?: return SyncOutcome.Success
            if (token == push.pushTokenSent) return SyncOutcome.Success

            return try {
                api.registerPushToken(token)
                push.pushTokenSent = token
                SyncOutcome.Success
            } catch (_: ApiException.Rejected) {
                // Refused outright, so no retry can fix it — the same call the
                // outbox makes when it marks a row dead. Recorded as sent to stop
                // asking on every sync; the next token FCM issues tries again.
                push.pushTokenSent = token
                SyncOutcome.Success
            } catch (error: ApiException) {
                error.toOutcome()
            }
        }

        /**
         * The only way a device hears about a list somebody else created and
         * invited it to. Catch-up asks per list and a list this device has never
         * heard of has no cursor to ask with, so without this a fresh install
         * would sign in successfully and show nothing at all.
         *
         * A newly discovered list goes in with an empty title: the changelog pull
         * that follows carries the `list-created` change that names it, and until
         * that lands the screen says so rather than inventing a name.
         */
        suspend fun discoverLists(): SyncOutcome {
            val memberships =
                try {
                    api.memberships()
                } catch (error: ApiException) {
                    return error.toOutcome()
                }

            for (membership in memberships) {
                val known = db.lists().find(membership.listId)
                if (known == null) {
                    db.lists().upsert(
                        ListEntity(
                            id = membership.listId,
                            title = "",
                            role = membership.role,
                            position = membership.position,
                        ),
                    )
                    continue
                }

                if (known.role != membership.role) {
                    db.lists().setRole(membership.listId, membership.role)
                }
                // The outbox drains before this runs, so anything the server
                // reports back is at least as new as what was dragged here. A
                // drag made after this call is a new outbox row, not a lost one.
                if (known.position != membership.position) {
                    db.lists().setPosition(membership.listId, membership.position)
                }
            }
            return SyncOutcome.Success
        }

        /**
         * Sends pending mutations oldest first, one at a time.
         *
         * One at a time is not laziness: two mutations for the same entity have to
         * reach the server in the order the user made them, because the server
         * resolves conflicts per field by the timestamp it stamps on arrival (F5.4).
         *
         * The row is deleted only after the server has acknowledged it, in the same
         * transaction that applies the echo. A process killed anywhere in between
         * replays the same row with the same key on next launch; the server returns
         * the original result and the second apply is a no-op (H3.4, F5.2).
         */
        suspend fun drainOutbox(): SyncOutcome {
            val gapped = mutableSetOf<String>()

            while (true) {
                val row = db.outbox().pending(1).firstOrNull() ?: break
                when (val result = send(row)) {
                    is SendResult.Done -> if (result.gap) gapped += row.listId
                    is SendResult.Dead -> Unit // Skipped from here on; the row stays.
                    is SendResult.Stop -> return result.outcome
                }
            }

            for (listId in gapped) {
                val outcome = catchUp(listId)
                if (outcome != SyncOutcome.Success) return outcome
            }
            return SyncOutcome.Success
        }

        /** Pulls every list this device knows about up to the server head. */
        suspend fun catchUpAll(): SyncOutcome {
            for (list in db.lists().knownIds()) {
                val outcome = catchUp(list)
                if (outcome != SyncOutcome.Success) return outcome
            }
            return SyncOutcome.Success
        }

        /**
         * `GET ?since=cursor`, applied through [ChangeApplier] — the same function
         * the socket path uses (F5.6). Pages until the server stops truncating.
         */
        suspend fun catchUp(listId: String): SyncOutcome {
            while (true) {
                val since = db.syncState().cursor(listId) ?: 0L
                val page =
                    try {
                        api.changes(listId, since)
                    } catch (error: ApiException) {
                        return error.toOutcome()
                    }

                applier.noteServerMaxSeq(listId, page.maxSeq)
                for (change in page.changes) applier.apply(change)

                // An empty page that still claims more would loop forever. The
                // cursor has not moved, so there is nothing to gain by asking again.
                if (!page.truncated || page.changes.isEmpty()) return SyncOutcome.Success
            }
        }

        private suspend fun send(row: OutboxEntity): SendResult =
            try {
                if (row.entityType == OutboxKind.CLAIM) {
                    api.claimList(row.listId)
                    db.outbox().delete(row.id)
                    SendResult.Done(gap = false)
                } else if (row.entityType == OutboxKind.ORDER) {
                    // Not a list mutation: it lands on this account's membership
                    // row, so there is no ack to apply and no changelog to fall
                    // behind. The local write already happened when the user dragged.
                    val request =
                        DielysJson.wire.decodeFromString(
                            SetListPositionRequest.serializer(),
                            row.body,
                        )
                    api.setListPosition(request.listId, request.position)
                    db.outbox().delete(row.id)
                    SendResult.Done(gap = false)
                } else {
                    val ack = api.mutate(row.listId, row.body)
                    val outcome =
                        db.withTransaction {
                            val applied = applier.apply(ack.change)
                            db.outbox().delete(row.id)
                            applied
                        }
                    SendResult.Done(gap = outcome == ApplyOutcome.GAP)
                }
            } catch (error: ApiException.Rejected) {
                // No retry can fix this one. The row is marked rather than deleted:
                // silently dropping something the user typed is worse than leaving
                // it visible and stuck.
                db.outbox().markDead(row.id, "${error.status} ${error.code}")
                SendResult.Dead
            } catch (error: ApiException) {
                db.outbox().recordFailure(row.id, error.message ?: "unknown")
                SendResult.Stop(error.toOutcome())
            }

        private sealed interface SendResult {
            data class Done(
                val gap: Boolean,
            ) : SendResult

            data object Dead : SendResult

            data class Stop(
                val outcome: SyncOutcome,
            ) : SendResult
        }
    }

private fun ApiException.toOutcome(): SyncOutcome =
    when (this) {
        is ApiException.Unauthorized -> SyncOutcome.SessionExpired(this)
        else -> SyncOutcome.Retry(this)
    }
