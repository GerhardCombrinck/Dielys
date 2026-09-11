package za.co.dielys.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import za.co.dielys.data.local.DeviceIdentity
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.ListEntity
import za.co.dielys.data.local.ListItemCount
import za.co.dielys.data.local.OutboxEntity
import za.co.dielys.data.local.TaskEntity
import za.co.dielys.data.remote.ListPatch
import za.co.dielys.data.remote.TaskPatch
import za.co.dielys.data.sync.OutboxFactory
import za.co.dielys.data.sync.SyncScheduler
import za.co.dielys.domain.Clock
import za.co.dielys.domain.Position
import za.co.dielys.domain.Timestamps
import za.co.dielys.domain.Uuid7
import za.co.dielys.domain.seedPositions
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every user action, and the only thing the UI calls.
 *
 * Two rules hold for all of them, and neither can be tested server-side:
 *
 * - The local write and its outbox row commit in **one** transaction (F5.7). Never
 *   the entity first and the outbox row after in a separate call: a crash between
 *   the two would leave an edit the user can see and the server will never hear
 *   about, or an outbox row for a row that does not exist.
 * - Nothing here blocks on the network. The write lands locally, the screen
 *   updates from Room, and [SyncScheduler] is only asked to *try* — offline is the
 *   normal case, not the error case.
 */
@Singleton
class DielysRepository
    @Inject
    constructor(
        private val db: DielysDatabase,
        private val outbox: OutboxFactory,
        private val session: DeviceIdentity,
        private val scheduler: SyncScheduler,
        private val clock: Clock,
    ) {
        fun observeLists(): Flow<List<ListEntity>> = db.lists().observeAll()

        fun observeList(listId: String): Flow<ListEntity?> = db.lists().observe(listId)

        fun observeTasks(listId: String): Flow<List<TaskEntity>> = db.tasks().observeInList(listId)

        /** For the item count on each row of the lists screen. */
        fun observeItemCounts(): Flow<List<ListItemCount>> = db.tasks().observeCountsByList()

        fun observePendingCount(): Flow<Int> = db.outbox().observePendingCount()

        /** Edits the server refused for good. Surfaced so they are not silent. */
        fun observeStuckCount(): Flow<Int> = db.outbox().observeDeadCount()

        /**
         * List ids are client-generated (F5.1), so the server cannot grant ownership
         * at creation time. The client picks a UUIDv7 and claims it, then names it.
         * Both steps queue, so creating a list offline works like everything else.
         */
        suspend fun createList(title: String): String {
            val id = Uuid7.generate(clock.nowMillis())
            val deviceId = session.deviceId
            val patch = ListPatch(title = title)

            commit(
                entity = { db.lists().upsert(ListEntity(id = id, title = title)) },
                rows =
                    listOf(
                        outbox.claim(id),
                        outbox.list(listId = id, entityId = id, deviceId = deviceId, patch = patch),
                    ),
            )
            return id
        }

        suspend fun renameList(
            listId: String,
            title: String,
        ) {
            val current = db.lists().find(listId) ?: return
            commitList(current.copy(title = title), listId, ListPatch(title = title))
        }

        suspend fun deleteList(listId: String) {
            val current = db.lists().find(listId) ?: return
            val deletedAt = Timestamps.iso(clock.nowMillis())
            commitList(
                current.copy(deletedAt = deletedAt),
                listId,
                ListPatch(deletedAt = deletedAt),
            )
        }

        /**
         * Prepends or appends among the *active* tasks, per [atTop] — a device
         * setting (UiPrefs), not list data, so two phones on the same list can
         * choose differently and this takes it as a plain argument rather than
         * reading it itself.
         *
         * Done tasks are ignored when finding the edge: they share the position
         * order but not the section, so a done task sitting at either end must
         * not become the new task's neighbour.
         *
         * Prepending lengthens the key, unlike appending, which is the price:
         * `between(null, first)` has to find room below an existing key rather
         * than incrementing past the last one (F5.5).
         */
        suspend fun addTask(
            listId: String,
            title: String,
            atTop: Boolean = true,
            id: String = Uuid7.generate(clock.nowMillis()),
        ): String {
            val active = db.tasks().inList(listId).filterNot { it.done }
            val position =
                if (atTop) {
                    Position.between(null, active.firstOrNull()?.position)
                } else {
                    Position.between(active.lastOrNull()?.position, null)
                }

            // A task create MUST carry title and position — the server invents
            // defaults for nothing only the client can know.
            val patch = TaskPatch(title = title, position = position)
            val task =
                TaskEntity(
                    id = id,
                    listId = listId,
                    title = title,
                    done = false,
                    starred = false,
                    position = position,
                )
            commitTask(task, patch)
            return id
        }

        /**
         * Marking a task done also promotes it to the top of the Done section —
         * the same fractional-index trick [setStarred] uses for "this one first"
         * (position is a shared axis across the whole list, `addTask`), applied
         * within the done subset instead of the whole list. Each completion lands
         * above every earlier one, so the section stays sorted most-recently-done
         * first with no separate ordering field to keep in sync.
         *
         * Un-completing leaves the position alone — same reasoning as unstarring:
         * the honest answer to "where does it go back to" is "wherever it was".
         */
        suspend fun setDone(
            taskId: String,
            done: Boolean,
        ) {
            val task = db.tasks().find(taskId) ?: return
            if (!done) {
                commitTask(task.copy(done = false), TaskPatch(done = false))
                return
            }

            val firstDone = db.tasks().inList(task.listId).firstOrNull { it.done }
            // Already the most recently completed, or the only done row: must not
            // mint a key below its own.
            val position =
                if (firstDone == null || firstDone.id == task.id) {
                    task.position
                } else {
                    Position.between(null, firstDone.position)
                }

            commitTask(
                task.copy(done = true, position = position),
                TaskPatch(done = true, position = position),
            )
        }

        /**
         * Starring also moves the task to the top — the star is what somebody
         * uses to say "this one first", and leaving it in place halfway down the
         * list makes them do the dragging themselves.
         *
         * Unstarring leaves it where it is. Sending it back down would mean
         * remembering where it came from, and the honest answer to "where does an
         * unstarred item belong" is "wherever the person put it".
         *
         * One patch, so the two land together: a device that saw the star without
         * the move would show it in the old place until the next change arrived.
         */
        suspend fun setStarred(
            taskId: String,
            starred: Boolean,
        ) {
            val task = db.tasks().find(taskId) ?: return
            if (!starred) {
                commitTask(task.copy(starred = false), TaskPatch(starred = false))
                return
            }

            val first = db.tasks().inList(task.listId).firstOrNull()
            // Already at the top, or the only row: starring must not mint a key
            // below its own.
            val position =
                if (first == null || first.id == task.id) {
                    task.position
                } else {
                    Position.between(null, first.position)
                }

            commitTask(
                task.copy(starred = true, position = position),
                TaskPatch(starred = true, position = position),
            )
        }

        suspend fun renameTask(
            taskId: String,
            title: String,
        ) {
            val task = db.tasks().find(taskId) ?: return
            commitTask(task.copy(title = title), TaskPatch(title = title))
        }

        /** A delete is a tombstone, never a `DELETE` (F5.3). */
        suspend fun deleteTask(taskId: String) {
            val task = db.tasks().find(taskId) ?: return
            val deletedAt = Timestamps.iso(clock.nowMillis())
            commitTask(task.copy(deletedAt = deletedAt), TaskPatch(deletedAt = deletedAt))
        }

        /**
         * Moves one task between two others. Exactly one row is written — a reorder
         * never renumbers siblings (F5.5), which is what makes two devices
         * reordering the same list offline merge instead of fight (H3.9).
         *
         * [afterId] is the task the moved one should land below, [beforeId] the one
         * it should land above. Null means the end of the list on that side.
         */
        suspend fun moveTask(
            taskId: String,
            afterId: String?,
            beforeId: String?,
        ) {
            val task = db.tasks().find(taskId) ?: return
            val after = afterId?.let { db.tasks().find(it) }
            val before = beforeId?.let { db.tasks().find(it) }
            val position = Position.between(after?.position, before?.position)
            commitTask(task.copy(position = position), TaskPatch(position = position))
        }

        /**
         * Moves one list in this account's own order — the lists screen's drag.
         *
         * Not a list mutation: it goes to `UsersRoom` as a membership position
         * (PROTOCOL.md "Ordering the lists"), so the other person on a shared list
         * keeps their own order. Same fractional index as a task, and the same
         * one-row rule: a move never renumbers the lists around it (F5.5).
         *
         * Lists that predate this feature have no key at all. Rather than invent
         * one for the whole screen on first launch, the first drag seeds every
         * list that is missing one, in the order they are already shown — so what
         * the person sees before the drag is what they see after it, minus the row
         * they moved.
         */
        suspend fun moveList(
            listId: String,
            afterId: String?,
            beforeId: String?,
        ) {
            val ordered = db.lists().all()
            if (ordered.none { it.id == listId }) return

            val seeded =
                seedPositions(
                    ordered,
                    positionOf = { it.position },
                    withPosition = { list, key -> list.copy(position = key) },
                )
            val byId = seeded.associateBy { it.id }
            val moved = byId[listId] ?: return
            val position =
                Position.between(byId[afterId]?.position, byId[beforeId]?.position)

            // Rows the seed gave a key to — the ones that had none — plus the move
            // itself. All of them are this account's own membership rows, so they
            // queue as ORDER rows rather than as list mutations.
            val unseeded = ordered.filter { it.position == null }.map { it.id }.toSet()
            val seedWrites = seeded.filter { it.id in unseeded && it.id != listId }

            commit(
                entity = {
                    for (list in seedWrites) db.lists().upsert(list)
                    db.lists().upsert(moved.copy(position = position))
                },
                rows =
                    seedWrites.map {
                        // seedPositions leaves none of these null.
                        outbox.order(listId = it.id, position = checkNotNull(it.position))
                    } +
                        outbox.order(listId = listId, position = position),
            )
        }

        private suspend fun commitTask(
            task: TaskEntity,
            patch: TaskPatch,
        ) {
            val row =
                outbox.task(
                    listId = task.listId,
                    entityId = task.id,
                    deviceId = session.deviceId,
                    patch = patch,
                )
            commit(entity = { db.tasks().upsert(task) }, rows = listOf(row))
        }

        private suspend fun commitList(
            list: ListEntity,
            listId: String,
            patch: ListPatch,
        ) {
            val row =
                outbox.list(
                    listId = listId,
                    entityId = listId,
                    deviceId = session.deviceId,
                    patch = patch,
                )
            commit(entity = { db.lists().upsert(list) }, rows = listOf(row))
        }

        /** F5.7, in one place so no caller can get it wrong. */
        private suspend fun commit(
            entity: suspend () -> Unit,
            rows: List<OutboxEntity>,
        ) {
            db.withTransaction {
                entity()
                for (row in rows) db.outbox().enqueue(row)
            }
            scheduler.requestSync()
        }
    }
