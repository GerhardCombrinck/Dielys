package za.co.dielys.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack
import za.co.dielys.data.remote.Membership
import za.co.dielys.data.remote.MembershipRole

/**
 * Catch-up asks per list, so a list this device has never heard of has no cursor
 * to ask with. `GET /auth/memberships` is the only thing that closes that circle —
 * without it a second phone signs in successfully and shows an empty screen
 * forever, which is the kind of bug that looks like "sync is broken".
 */
@RunWith(RobolectricTestRunner::class)
class ListDiscoveryTest {
    private val api = FakeSyncApi()
    private val devices = mutableListOf<DeviceStack>()

    @After
    fun close() = devices.forEach { it.close() }

    @Test
    fun `a device that knows nothing learns its lists and then their contents`() =
        runTest {
            val alice = device("device-a")
            val listId = alice.repo.createList("Groceries")
            alice.repo.addTask(listId, "Milk")
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            val bob = device("device-b")
            assertEquals(emptyList<String>(), bob.db.lists().knownIds())

            assertEquals(SyncOutcome.Success, bob.engine.sync())

            assertEquals(listOf(listId), bob.db.lists().knownIds())
            assertEquals(
                "Groceries",
                bob.db
                    .lists()
                    .find(listId)
                    ?.title,
            )
            assertEquals(
                listOf("Milk"),
                bob.db
                    .tasks()
                    .inList(listId)
                    .map { it.title },
            )
            assertEquals(
                MembershipRole.OWNER,
                bob.db
                    .lists()
                    .find(listId)
                    ?.role,
            )
        }

    /** An invite accepted on the other phone reaches this one the same way. */
    @Test
    fun `a list this device was invited to arrives with the next sync`() =
        runTest {
            val alice = device("device-a")
            val listId = alice.repo.createList("Braai")
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            val bob = device("device-b")
            // The fake keeps one membership table, so this is the server now
            // answering for Bob's account rather than Alice's.
            api.memberOf.clear()
            api.memberOf += Membership(listId = listId, role = MembershipRole.MEMBER)

            assertEquals(SyncOutcome.Success, bob.engine.sync())
            assertEquals(
                "Braai",
                bob.db
                    .lists()
                    .find(listId)
                    ?.title,
            )
            assertEquals(
                MembershipRole.MEMBER,
                bob.db
                    .lists()
                    .find(listId)
                    ?.role,
            )
        }

    @Test
    fun `no network means no lists rather than a wrong answer`() =
        runTest {
            val bob = device("device-b")
            api.memberOf += Membership(listId = "list-1", role = MembershipRole.MEMBER)
            api.online = false

            assertTrue(bob.engine.sync() is SyncOutcome.Retry)
            assertEquals(emptyList<String>(), bob.db.lists().knownIds())
        }

    private fun device(id: String): DeviceStack = DeviceStack(api, id).also { devices += it }
}
