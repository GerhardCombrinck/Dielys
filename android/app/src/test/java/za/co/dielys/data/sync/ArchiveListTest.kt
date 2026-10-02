package za.co.dielys.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack
import za.co.dielys.data.remote.DielysJson
import za.co.dielys.data.remote.ListMutation
import za.co.dielys.data.remote.Mutation

/**
 * Archiving a list and bringing it back for the next trip (ADR 0014), across
 * two phones: what one does, the other sees, through the same changelog as any
 * other edit.
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveListTest {
    private val api = FakeSyncApi()
    private val devices = mutableListOf<DeviceStack>()

    @After
    fun close() = devices.forEach { it.close() }

    @Test
    fun `an archived list is archived on the other phone too, and restoring brings it back`() =
        runTest {
            val alice = device("device-a")
            val bob = device("device-b")
            val listId = alice.repo.createList("Kampeer")
            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(SyncOutcome.Success, bob.engine.catchUp(listId))

            alice.repo.setArchived(listId, archived = true)
            assertTrue(
                alice.db
                    .lists()
                    .find(listId)!!
                    .archived,
            )
            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(SyncOutcome.Success, bob.engine.catchUp(listId))
            assertTrue(
                bob.db
                    .lists()
                    .find(listId)!!
                    .archived,
            )

            alice.repo.setArchived(listId, archived = false)
            assertEquals(SyncOutcome.Success, alice.engine.sync())
            // Restoring has to be sent as a value: Android's outbound JSON drops
            // nulls, which is why the field is a boolean and not a timestamp.
            assertEquals(false, lastSent<ListMutation>().patch.archived)
            assertEquals(SyncOutcome.Success, bob.engine.catchUp(listId))
            assertFalse(
                bob.db
                    .lists()
                    .find(listId)!!
                    .archived,
            )
        }

    @Test
    fun `restoring with untick clears every tick, on both phones, and leaves the rest alone`() =
        runTest {
            val alice = device("device-a")
            val bob = device("device-b")
            val listId = alice.repo.createList("Kampeer")
            val tent = alice.repo.addTask(listId, "Tent")
            val torch = alice.repo.addTask(listId, "Torch")
            alice.repo.addTask(listId, "Gas")
            alice.repo.setDone(tent, done = true)
            alice.repo.setDone(torch, done = true)
            alice.repo.setArchived(listId, archived = true)
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            alice.repo.setArchived(listId, archived = false, untick = true)

            // The restore and both unticks, in one transaction (F5.7): the list
            // and its items come back together.
            val queued = alice.db.outbox().pending(LIMIT)
            assertEquals(listOf("list", "task", "task"), queued.map { it.entityType })
            assertTrue(
                alice.db
                    .tasks()
                    .inList(listId)
                    .none { it.done },
            )

            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(SyncOutcome.Success, bob.engine.catchUp(listId))
            assertFalse(
                bob.db
                    .lists()
                    .find(listId)!!
                    .archived,
            )
            val onBob = bob.db.tasks().inList(listId)
            assertEquals(setOf("Tent", "Torch", "Gas"), onBob.map { it.title }.toSet())
            assertTrue(onBob.none { it.done })
        }

    @Test
    fun `restoring without untick leaves the ticks where they were`() =
        runTest {
            val phone = device("device-a")
            val listId = phone.repo.createList("Kampeer")
            val tent = phone.repo.addTask(listId, "Tent")
            phone.repo.setDone(tent, done = true)
            phone.repo.setArchived(listId, archived = true)

            phone.repo.setArchived(listId, archived = false)

            assertTrue(
                phone.db
                    .tasks()
                    .find(tent)!!
                    .done,
            )
            assertFalse(
                phone.db
                    .lists()
                    .find(listId)!!
                    .archived,
            )
        }

    private fun device(name: String): DeviceStack = DeviceStack(api, name).also { devices += it }

    private inline fun <reified T : Mutation> lastSent(): T =
        api.sentBodies
            .map { DielysJson.wire.decodeFromString(Mutation.serializer(), it) }
            .filterIsInstance<T>()
            .last()

    private companion object {
        const val LIMIT = 50
    }
}
