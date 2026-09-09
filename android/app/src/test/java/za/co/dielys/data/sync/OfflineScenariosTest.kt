package za.co.dielys.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack
import za.co.dielys.data.remote.ErrorCode
import za.co.dielys.data.remote.TaskMutation
import za.co.dielys.data.remote.TaskPatch

/**
 * The offline scenarios from H3 that cannot be tested server-side, because the
 * thing under test is what the client does when the server is not there.
 *
 * Real Room, real SQLite, real fractional indexing; only the network is a fake.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineScenariosTest {
    private val api = FakeSyncApi()
    private val devices = mutableListOf<DeviceStack>()

    @After
    fun close() = devices.forEach { it.close() }

    /** H3.1 — a write made with no network, and what happens when it comes back. */
    @Test
    fun `a write made offline is held and drains when the network returns`() =
        runTest {
            val phone = device("device-a")
            api.online = false

            val listId = phone.repo.createList("Groceries")
            val taskId = phone.repo.addTask(listId, "Milk")

            // The screen already shows it: the write did not wait for anything.
            assertEquals(
                listOf("Milk"),
                phone.db
                    .tasks()
                    .inList(listId)
                    .map { it.title },
            )
            // Claim, list, task — F5.7, all committed with their entities.
            assertEquals(
                3,
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .size,
            )
            assertEquals(2, phone.scheduler.requests)
            assertEquals(null, phone.db.syncState().cursor(listId))

            // Draining into a hole leaves everything exactly where it was.
            assertTrue(phone.engine.sync() is SyncOutcome.Retry)
            assertEquals(
                3,
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .size,
            )

            api.online = true
            assertEquals(SyncOutcome.Success, phone.engine.sync())

            assertTrue(
                phone.db
                    .outbox()
                    .all()
                    .isEmpty(),
            )
            assertEquals(listOf(listId), api.claims)
            // F5.8: the cursor moved because the changes were committed locally,
            // not because the server mentioned them.
            assertEquals(2L, phone.db.syncState().cursor(listId))
            // The echo filled in the timestamp the optimistic write left null.
            assertNotNull(
                phone.db
                    .tasks()
                    .find(taskId)
                    ?.updatedAt,
            )
        }

    /** H3.4 — the process dies after the server applied but before the ack lands. */
    @Test
    fun `a drain killed after the server applied replays the same key`() =
        runTest {
            val phone = device("device-a")
            val listId = phone.repo.createList("Groceries")
            assertEquals(SyncOutcome.Success, phone.engine.sync())

            api.loseNextResponse = true
            phone.repo.addTask(listId, "Bread")
            assertTrue(phone.engine.sync() is SyncOutcome.Retry)

            // The server has it; this device has no idea. The row must survive.
            val stranded =
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .single()
            assertEquals(1, stranded.attempts)

            // Next launch: same row, same body, same key (F5.2).
            val restarted = SyncEngine(phone.db, api, phone.applier, phone.push)
            assertEquals(SyncOutcome.Success, restarted.sync())

            assertEquals(stranded.body, api.sentBodies.last())
            assertEquals(2, api.sentBodies.count { it == stranded.body })
            assertTrue(
                phone.db
                    .outbox()
                    .all()
                    .isEmpty(),
            )
            // One task, not two, and the seq never moved past 2.
            assertEquals(
                listOf("Bread"),
                phone.db
                    .tasks()
                    .inList(listId)
                    .map { it.title },
            )
            assertEquals(2L, phone.db.syncState().cursor(listId))
        }

    /** H3.9 — both devices drag something into the same gap while offline. */
    @Test
    fun `two devices reordering the same list offline converge`() =
        runTest {
            val alice = device("device-a")
            val bob = device("device-b")

            val listId = alice.repo.createList("Groceries")
            val ids = TITLES.map { alice.repo.addTask(listId, it) }
            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(SyncOutcome.Success, bob.engine.catchUp(listId))

            val (milk, bread, eggs, jam) = ids
            val beforeMoves = api.sentBodies.size
            api.online = false
            alice.repo.moveTask(eggs, afterId = milk, beforeId = bread)
            bob.repo.moveTask(jam, afterId = milk, beforeId = bread)

            // Same gap, same key, no coordination — that is the whole point of a
            // fractional index (F5.5), and it is why the id has to break the tie.
            assertEquals(
                alice.db
                    .tasks()
                    .find(eggs)
                    ?.position,
                bob.db
                    .tasks()
                    .find(jam)
                    ?.position,
            )

            api.online = true
            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(SyncOutcome.Success, bob.engine.sync())
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            val expected = listOf(milk) + listOf(eggs, jam).sorted() + listOf(bread)
            assertEquals(
                expected,
                alice.db
                    .tasks()
                    .inList(listId)
                    .map { it.id },
            )
            assertEquals(
                expected,
                bob.db
                    .tasks()
                    .inList(listId)
                    .map { it.id },
            )
            // One moved row per device and nothing else: a reorder never
            // renumbers siblings (F5.5), which is what let both moves survive.
            assertEquals(2, api.sentBodies.size - beforeMoves)
        }

    /** F5.6 — a change from the future is never applied out of order. */
    @Test
    fun `a change beyond the cursor is refused and filled in by a catch-up`() =
        runTest {
            val phone = device("device-a")
            val listId = phone.repo.createList("Groceries")
            assertEquals(SyncOutcome.Success, phone.engine.sync())

            val missed = (0 until 3).map { n -> otherDeviceTask(listId, n) }
            val live = missed.last()

            assertEquals(ApplyOutcome.GAP, phone.applier.apply(live))
            assertEquals(1L, phone.db.syncState().cursor(listId))
            assertTrue(
                phone.db
                    .tasks()
                    .inList(listId)
                    .isEmpty(),
            )

            assertEquals(SyncOutcome.Success, phone.engine.catchUp(listId))
            assertEquals(4L, phone.db.syncState().cursor(listId))
            assertEquals(
                3,
                phone.db
                    .tasks()
                    .inList(listId)
                    .size,
            )
            // Redelivery of the one that arrived early is now a no-op (F5.2).
            assertEquals(ApplyOutcome.ALREADY_APPLIED, phone.applier.apply(live))
        }

    @Test
    fun `catch-up keeps pulling while the server truncates`() =
        runTest {
            val phone = device("device-a")
            val listId = phone.repo.createList("Groceries")
            assertEquals(SyncOutcome.Success, phone.engine.sync())
            for (n in 0 until 5) otherDeviceTask(listId, n)
            api.pageSize = 2

            assertEquals(SyncOutcome.Success, phone.engine.catchUp(listId))

            assertEquals(6L, phone.db.syncState().cursor(listId))
            assertEquals(
                5,
                phone.db
                    .tasks()
                    .inList(listId)
                    .size,
            )
        }

    @Test
    fun `a rejected mutation is marked dead rather than dropped`() =
        runTest {
            val phone = device("device-a")
            val listId = phone.repo.createList("Groceries")
            assertEquals(SyncOutcome.Success, phone.engine.sync())

            api.rejectNextWith = ErrorCode.MALFORMED
            phone.repo.addTask(listId, "Milk")
            phone.repo.addTask(listId, "Bread")
            assertEquals(SyncOutcome.Success, phone.engine.sync())

            val dead =
                phone.db
                    .outbox()
                    .all()
                    .single()
            assertTrue(dead.dead)
            assertEquals("400 malformed", dead.lastError)
            // Kept, and still on screen. Dropping what the user typed is worse.
            assertEquals(
                listOf("Milk", "Bread"),
                phone.db
                    .tasks()
                    .inList(listId)
                    .map { it.title },
            )
            // The row behind it still went, so one bad row cannot wedge the queue.
            assertEquals(2L, phone.db.syncState().cursor(listId))
        }

    @Test
    fun `an expired session stops the drain and keeps every row`() =
        runTest {
            val phone = device("device-a")
            phone.repo.createList("Groceries")
            api.unauthorized = true

            assertTrue(phone.engine.sync() is SyncOutcome.SessionExpired)

            assertEquals(
                2,
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .size,
            )
            assertTrue(api.claims.isEmpty())
        }

    private fun device(name: String): DeviceStack = DeviceStack(api, name).also { devices += it }

    /** A task the other phone created while this one was not looking. */
    private fun otherDeviceTask(
        listId: String,
        n: Int,
    ) = api.otherDevice(
        TaskMutation(
            listId = listId,
            idempotencyKey = "other-key-$n",
            deviceId = "device-b",
            entityId = "other-task-$n",
            patch = TaskPatch(title = "Item $n", position = "a$n"),
        ),
    )

    private companion object {
        const val LIMIT = 50
        val TITLES = listOf("Milk", "Bread", "Eggs", "Jam")
    }
}
