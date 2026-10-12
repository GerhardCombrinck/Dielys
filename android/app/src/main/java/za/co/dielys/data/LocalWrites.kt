package za.co.dielys.data

import androidx.room.withTransaction
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.OutboxEntity
import za.co.dielys.data.local.TaskDao
import za.co.dielys.data.sync.SyncScheduler
import za.co.dielys.domain.Position
import za.co.dielys.domain.spotUnderStarred

/**
 * F5.7, in one place so no caller can get it wrong: the local write and its
 * outbox rows commit in **one** transaction, then a sync is asked for. Shared
 * by [DielysRepository] and [TaskMover].
 */
internal suspend fun DielysDatabase.commitLocal(
    scheduler: SyncScheduler,
    rows: List<OutboxEntity>,
    entity: suspend () -> Unit,
) {
    withTransaction {
        entity()
        for (row in rows) outbox().enqueue(row)
    }
    scheduler.requestSync()
}

/**
 * Where a new task lands in [listId] — see [DielysRepository.addTask] for the
 * rules: under the starred rows when [atTop], after the last still-to-do row
 * otherwise.
 */
internal suspend fun TaskDao.newTaskPosition(
    listId: String,
    atTop: Boolean,
): String {
    val active = inList(listId).filterNot { it.done }
    return if (atTop) {
        val spot = spotUnderStarred(active) { it.starred }
        Position.between(
            active.getOrNull(spot - 1)?.position,
            active.getOrNull(spot)?.position,
        )
    } else {
        Position.between(active.lastOrNull()?.position, null)
    }
}
