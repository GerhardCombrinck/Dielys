package za.co.dielys.data.notify

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack
import za.co.dielys.data.local.ListActivityEntity
import za.co.dielys.data.remote.NotifyEvent
import za.co.dielys.data.sync.FakeSyncApi
import za.co.dielys.data.sync.SyncOutcome

/**
 * What a phone decides is news on a shared list (ADR 0012), with two phones
 * against one fake server — Alice writing, Bob listening, Bob's phone in his
 * pocket unless a test says otherwise.
 *
 * Only the lines are checked here; turning them into a notification is
 * [ListNotifierTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
class ListActivityTest {
    private val api = FakeSyncApi()
    private val devices = mutableListOf<DeviceStack>()
    private lateinit var alice: DeviceStack
    private lateinit var bob: DeviceStack
    private lateinit var listId: String

    @Before
    fun setUp() =
        runTest {
            alice = device("device-a")
            bob = device("device-b")
            listId = alice.repo.createList("Groceries")
            assertEquals(SyncOutcome.Success, alice.engine.sync())
            // The fake keeps one membership table for the household; two people
            // on it is what makes the list shared.
            shareList()
            // Bob's first pull: the list's history, which is never news.
            assertEquals(SyncOutcome.Success, bob.engine.sync())
        }

    @After
    fun close() = devices.forEach { it.close() }

    @Test
    fun `somebody else's add on a list Bob follows becomes a line naming them`() =
        runTest {
            subscribe(NotifyEvent.ADDED)

            alice.repo.addTask(listId, "Milk")
            alice.engine.sync()
            bob.engine.sync()

            val lines = lines()
            assertEquals(listOf(NotifyEvent.ADDED to "Milk"), lines.map { it.kind to it.title })
            assertEquals("device-a-user", lines.single().authorUserId)
        }

    @Test
    fun `the choice goes to the server as this account's, and only the kinds chosen count`() =
        runTest {
            subscribe(NotifyEvent.CHECKED)
            assertEquals(
                listOf(NotifyEvent.CHECKED),
                api.memberOf.single { it.listId == listId }.notify,
            )

            val milk = alice.repo.addTask(listId, "Milk")
            alice.engine.sync()
            bob.engine.sync()
            assertEquals(emptyList<ListActivityEntity>(), lines())

            alice.repo.setDone(milk, true)
            alice.engine.sync()
            bob.engine.sync()
            assertEquals(listOf(NotifyEvent.CHECKED), lines().map { it.kind })
        }

    @Test
    fun `nothing is news until Bob asks for it`() =
        runTest {
            alice.repo.addTask(listId, "Milk")
            alice.engine.sync()
            bob.engine.sync()

            assertEquals(emptyList<ListActivityEntity>(), lines())
        }

    @Test
    fun `this account's own edits are never news, from any of its devices`() =
        runTest {
            subscribe(NotifyEvent.ADDED)
            // Bob's tablet: another device, the same account.
            val tablet = device("device-c")
            tablet.account.userId = bob.account.userId
            api.authors["device-c"] = checkNotNull(bob.account.userId)
            tablet.engine.sync()

            bob.repo.addTask(listId, "Bread")
            tablet.repo.addTask(listId, "Eggs")
            bob.engine.sync()
            tablet.engine.sync()
            bob.engine.sync()

            assertEquals(emptyList<ListActivityEntity>(), lines())
        }

    @Test
    fun `nothing is news while Bob is looking at the app`() =
        runTest {
            subscribe(NotifyEvent.ADDED)
            bob.visibility.visible = true

            alice.repo.addTask(listId, "Milk")
            alice.engine.sync()
            bob.engine.sync()

            assertEquals(emptyList<ListActivityEntity>(), lines())
        }

    @Test
    fun `a list that is no longer shared has nobody else to hear about`() =
        runTest {
            subscribe(NotifyEvent.ADDED)
            val index = api.memberOf.indexOfFirst { it.listId == listId }
            api.memberOf[index] = api.memberOf[index].copy(memberCount = 1)
            bob.engine.sync()

            alice.repo.addTask(listId, "Milk")
            alice.engine.sync()
            bob.engine.sync()

            assertEquals(emptyList<ListActivityEntity>(), lines())
        }

    @Test
    fun `a change with no author is unknown, not somebody else`() =
        runTest {
            subscribe(NotifyEvent.ADDED)
            api.authors.remove("device-a")

            alice.repo.addTask(listId, "Milk")
            alice.engine.sync()
            bob.engine.sync()

            assertEquals(emptyList<ListActivityEntity>(), lines())
        }

    @Test
    fun `a task renamed twice while Bob slept is one line with its latest name`() =
        runTest {
            subscribe(NotifyEvent.UPDATED)
            val milk = alice.repo.addTask(listId, "Milk")
            alice.engine.sync()
            bob.engine.sync()

            alice.repo.renameTask(milk, "Oat milk")
            alice.engine.sync()
            alice.repo.renameTask(milk, "Almond milk")
            alice.engine.sync()
            bob.engine.sync()

            assertEquals(
                listOf(NotifyEvent.UPDATED to "Almond milk"),
                lines().map {
                    it.kind to
                        it.title
                },
            )
        }

    /**
     * A new phone, or one that has just joined, reads a list from the start of
     * its history — across several pages when it is long. None of it is news,
     * even with a subscription already on the server from another device.
     */
    @Test
    fun `a first pull is quiet on every page, not just the first`() =
        runTest {
            repeat(5) { alice.repo.addTask(listId, "Item $it") }
            alice.engine.sync()
            val index = api.memberOf.indexOfFirst { it.listId == listId }
            api.memberOf[index] = api.memberOf[index].copy(notify = NotifyEvent.ALL)
            api.pageSize = 2

            val phone = device("device-d")
            assertEquals(SyncOutcome.Success, phone.engine.sync())

            assertEquals(
                5,
                phone.db
                    .tasks()
                    .inList(listId)
                    .size,
            )
            assertEquals(emptyList<ListActivityEntity>(), phone.db.listActivity().forList(listId))

            // And the next change after it is news again.
            alice.repo.addTask(listId, "Milk")
            alice.engine.sync()
            phone.engine.sync()
            assertEquals(
                listOf("Milk"),
                phone.db
                    .listActivity()
                    .forList(listId)
                    .map { it.title },
            )
        }

    @Test
    fun `a choice made here is not undone by a server answer that has not seen it yet`() =
        runTest {
            bob.repo.setNotify(listId, setOf(NotifyEvent.ADDED))
            // Discovery without the drain first: the queued choice has not landed,
            // so the server still answers with none.
            bob.engine.discoverLists()

            assertEquals(
                setOf(NotifyEvent.ADDED),
                bob.db
                    .lists()
                    .find(listId)
                    ?.notify,
            )
        }

    private suspend fun subscribe(vararg events: String) {
        bob.repo.setNotify(listId, events.toSet())
        assertEquals(SyncOutcome.Success, bob.engine.sync())
    }

    private suspend fun lines(): List<ListActivityEntity> = bob.db.listActivity().forList(listId)

    private fun shareList() {
        val index = api.memberOf.indexOfFirst { it.listId == listId }
        api.memberOf[index] = api.memberOf[index].copy(memberCount = 2)
    }

    private fun device(id: String): DeviceStack =
        DeviceStack(api, id).also {
            devices += it
            api.authors[id] = checkNotNull(it.account.userId)
        }
}
