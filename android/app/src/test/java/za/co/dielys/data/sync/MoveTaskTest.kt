package za.co.dielys.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack

/**
 * Moving an item from one list to another (ADR 0015): a new task on the
 * target list and a tombstone on the source, written together, and seen that
 * way by the other phone.
 */
@RunWith(RobolectricTestRunner::class)
class MoveTaskTest {
    private val api = FakeSyncApi()
    private val devices = mutableListOf<DeviceStack>()

    @After
    fun close() = devices.forEach { it.close() }

    @Test
    fun `a moved item leaves one list and arrives on the other, on both phones`() =
        runTest {
            val alice = device("device-a")
            val bob = device("device-b")
            val groceries = alice.repo.createList("Groceries")
            val hardware = alice.repo.createList("Hardware")
            val bulbs = alice.repo.addTask(groceries, "Light bulbs")
            alice.repo.addTask(groceries, "Milk")
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            val moved = alice.repo.moveTaskToList(bulbs, hardware)
            assertNotNull(moved)
            // A fresh id: the old one is tombstoned and must stay that way (F5.3).
            assertNotEquals(bulbs, moved)

            // Both halves in one transaction (F5.7), so neither list ever shows
            // the item twice or not at all.
            val queued = alice.db.outbox().pending(LIMIT)
            assertEquals(listOf(hardware, groceries), queued.map { it.listId })
            assertEquals(
                listOf("Milk"),
                alice.db
                    .tasks()
                    .inList(groceries)
                    .map { it.title },
            )
            assertEquals(
                listOf("Light bulbs"),
                alice.db
                    .tasks()
                    .inList(hardware)
                    .map { it.title },
            )

            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(SyncOutcome.Success, bob.engine.catchUp(groceries))
            assertEquals(SyncOutcome.Success, bob.engine.catchUp(hardware))
            assertEquals(
                listOf("Milk"),
                bob.db
                    .tasks()
                    .inList(groceries)
                    .map { it.title },
            )
            assertEquals(
                listOf("Light bulbs"),
                bob.db
                    .tasks()
                    .inList(hardware)
                    .map { it.title },
            )
        }

    @Test
    fun `a starred item keeps its star and goes to the top`() =
        runTest {
            val phone = device("device-a")
            val groceries = phone.repo.createList("Groceries")
            val hardware = phone.repo.createList("Hardware")
            phone.repo.addTask(hardware, "Screws")
            phone.repo.addTask(hardware, "Paint")
            val bulbs = phone.repo.addTask(groceries, "Light bulbs")
            phone.repo.setStarred(bulbs, starred = true)

            phone.repo.moveTaskToList(bulbs, hardware)

            val onHardware = phone.db.tasks().inList(hardware)
            assertEquals("Light bulbs", onHardware.first().title)
            assertTrue(onHardware.first().starred)
        }

    @Test
    fun `moving onto the list it is already on does nothing`() =
        runTest {
            val phone = device("device-a")
            val groceries = phone.repo.createList("Groceries")
            val milk = phone.repo.addTask(groceries, "Milk")
            val before =
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .size

            assertNull(phone.repo.moveTaskToList(milk, groceries))
            assertEquals(
                before,
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .size,
            )
        }

    private fun device(name: String): DeviceStack = DeviceStack(api, name).also { devices += it }

    private companion object {
        const val LIMIT = 50
    }
}
