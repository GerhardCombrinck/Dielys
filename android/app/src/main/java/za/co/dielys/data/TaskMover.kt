package za.co.dielys.data

import za.co.dielys.data.local.DeviceIdentity
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.TaskEntity
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
 * Moves a task to another list, as a new task there and a tombstone here
 * (ADR 0015). A task belongs to one list's changelog and each list is its own
 * room, so there is no single write that could move it: the two halves go in
 * one local transaction and one outbox batch (F5.7), and each list hears about
 * its own half the ordinary way. Reached through [DielysRepository.moveTaskToList].
 */
@Singleton
class TaskMover
    @Inject
    constructor(
        private val db: DielysDatabase,
        private val outbox: OutboxFactory,
        private val session: DeviceIdentity,
        private val scheduler: SyncScheduler,
        private val clock: Clock,
    ) {
        /**
         * The copy gets a fresh id rather than reusing this one — an id the
         * source list has already tombstoned must never come back to life
         * anywhere (F5.3). It keeps its title and star; it lands where a new
         * item would per [atTop], except that a starred one goes to the very
         * top, the same place starring it would have put it.
         *
         * Returns the new task's id, or null if there was nothing to move.
         */
        suspend fun moveTaskToList(
            taskId: String,
            targetListId: String,
            atTop: Boolean,
        ): String? {
            val task = db.tasks().find(taskId) ?: return null
            if (task.listId == targetListId || task.deletedAt != null) return null

            val position =
                if (task.starred) {
                    Position.between(
                        null,
                        db
                            .tasks()
                            .inList(targetListId)
                            .firstOrNull()
                            ?.position,
                    )
                } else {
                    db.tasks().newTaskPosition(targetListId, atTop)
                }
            val copy =
                TaskEntity(
                    id = Uuid7.generate(clock.nowMillis()),
                    listId = targetListId,
                    title = task.title,
                    done = false,
                    starred = task.starred,
                    position = position,
                )
            val deletedAt = Timestamps.iso(clock.nowMillis())
            val gone = task.copy(deletedAt = deletedAt)

            val rows =
                listOf(
                    outbox.task(
                        listId = targetListId,
                        entityId = copy.id,
                        deviceId = session.deviceId,
                        // A create MUST carry title and position; the star rides
                        // along so nobody ever sees the copy without it.
                        patch =
                            TaskPatch(
                                title = copy.title,
                                position = position,
                                starred = true.takeIf { copy.starred },
                            ),
                    ),
                    outbox.task(
                        listId = task.listId,
                        entityId = task.id,
                        deviceId = session.deviceId,
                        patch = TaskPatch(deletedAt = deletedAt),
                    ),
                )
            db.commitLocal(scheduler, rows) {
                db.tasks().upsert(copy)
                db.tasks().upsert(gone)
            }
            return copy.id
        }
    }
