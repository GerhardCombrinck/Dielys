package za.co.dielys.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import za.co.dielys.data.local.DeviceIdentity
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.ListEntity
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

        fun observePendingCount(): Flow<Int> = db.outbox().observePendingCount()

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
         * Appends. The new key comes from the last task already in the list, so 62
         * additions in a row stay two characters (F5.5).
         */
        suspend fun addTask(
            listId: String,
            title: String,
        ): String {
            val id = Uuid7.generate(clock.nowMillis())
            val last = db.tasks().inList(listId).lastOrNull()
            val position = Position.between(last?.position, null)

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

        suspend fun setDone(
            taskId: String,
            done: Boolean,
        ) {
            val task = db.tasks().find(taskId) ?: return
            commitTask(task.copy(done = done), TaskPatch(done = done))
        }

        suspend fun setStarred(
            taskId: String,
            starred: Boolean,
        ) {
            val task = db.tasks().find(taskId) ?: return
            commitTask(task.copy(starred = starred), TaskPatch(starred = starred))
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
