package za.co.dielys.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack

/**
 * The background sync asks only the lists that have moved (PROTOCOL.md "Which
 * lists have changed"). Asking a quiet list is a request answered with nothing,
 * and on a half-hourly poll that was most of the app's traffic.
 *
 * The fake counts which lists `GET /changes` was asked about, which is the whole
 * of what this saves.
 */
@RunWith(RobolectricTestRunner::class)
class CatchUpSkipTest {
    private val api = FakeSyncApi()
    private val devices = mutableListOf<DeviceStack>()

    @After
    fun close() = devices.forEach { it.close() }

    @Test
    fun `a sync with nothing new anywhere asks no list for changes`() =
        runTest {
            val alice = device("device-a")
            alice.repo.createList("Groceries")
            alice.repo.createList("Braai")
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            api.changesAsked.clear()
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            assertEquals(emptyList<String>(), api.changesAsked)
        }

    @Test
    fun `only the list somebody else wrote to is asked`() =
        runTest {
            val alice = device("device-a")
            val groceries = alice.repo.createList("Groceries")
            alice.repo.createList("Braai")
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            val bob = device("device-b")
            assertEquals(SyncOutcome.Success, bob.engine.sync())
            bob.repo.addTask(groceries, "Milk")
            assertEquals(SyncOutcome.Success, bob.engine.sync())

            api.changesAsked.clear()
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            assertEquals(listOf(groceries), api.changesAsked)
            assertEquals(
                listOf("Milk"),
                alice.db
                    .tasks()
                    .inList(groceries)
                    .map { it.title },
            )
        }

    /**
     * The head is a lower bound: the room reports it without waiting, and a lost
     * report leaves a list looking current when it is not. Skipping on it is a
     * bet, and the daily sweep is what bounds the loss.
     */
    @Test
    fun `a list skipped on a lost head report is caught by the daily sweep`() =
        runTest {
            val alice = device("device-a")
            val groceries = alice.repo.createList("Groceries")
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            val bob = device("device-b")
            assertEquals(SyncOutcome.Success, bob.engine.sync())
            api.freezeHeads()
            bob.repo.addTask(groceries, "Milk")
            assertEquals(SyncOutcome.Success, bob.engine.sync())

            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(
                emptyList<String>(),
                alice.db
                    .tasks()
                    .inList(groceries)
                    .map { it.title },
            )

            // A day later, the heads are not trusted.
            alice.sweeps.lastFullCatchUpAt =
                alice.clock.nowMillis() - FULL_CATCH_UP_EVERY_MILLIS
            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(
                listOf("Milk"),
                alice.db
                    .tasks()
                    .inList(groceries)
                    .map { it.title },
            )
        }

    /** A server that predates `maxSeq` gets the old behaviour, not silence. */
    @Test
    fun `with no heads reported every list is asked every time`() =
        runTest {
            api.reportHeads = false
            val alice = device("device-a")
            val groceries = alice.repo.createList("Groceries")
            val braai = alice.repo.createList("Braai")
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            api.changesAsked.clear()
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            assertEquals(setOf(groceries, braai), api.changesAsked.toSet())
        }

    @Test
    fun `the sweep is due when never run, after a day, or when the clock went back`() {
        val now = 1_760_000_000_000L
        assertEquals(true, sweepDue(null, now))
        assertEquals(false, sweepDue(now - 1, now))
        assertEquals(true, sweepDue(now - FULL_CATCH_UP_EVERY_MILLIS, now))
        assertEquals(true, sweepDue(now + 60_000, now))
    }

    private fun device(id: String) = DeviceStack(api, id).also { devices += it }
}
