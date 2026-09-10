package za.co.dielys.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack
import za.co.dielys.data.remote.Membership
import za.co.dielys.data.remote.MembershipRole

/**
 * The order the lists appear in belongs to the account, not to the list
 * (PROTOCOL.md "Ordering the lists"). It rides the same outbox as everything
 * else, so a drag made with no signal lands when the signal comes back — and it
 * goes to `UsersRoom` rather than to a changelog, so the other person on a
 * shared list never sees it.
 */
@RunWith(RobolectricTestRunner::class)
class ListOrderTest {
    private val api = FakeSyncApi()
    private val devices = mutableListOf<DeviceStack>()

    @After
    fun close() = devices.forEach { it.close() }

    @Test
    fun `a drag reorders the screen and reaches the server`() =
        runTest {
            val phone = device("device-a")
            val first = phone.repo.createList("Inkopies")
            val second = phone.repo.createList("Braai")
            val third = phone.repo.createList("Hardeware")
            assertEquals(SyncOutcome.Success, phone.engine.sync())

            // Nothing has been dragged, so nothing has a key yet.
            assertTrue(
                phone.db
                    .lists()
                    .all()
                    .all { it.position == null },
            )
            val shown =
                phone.db
                    .lists()
                    .all()
                    .map { it.id }

            // The last one dragged to the top.
            phone.repo.moveList(third, afterId = null, beforeId = shown.first())

            assertEquals(
                listOf(third) + shown.filter { it != third },
                phone.db
                    .lists()
                    .all()
                    .map { it.id },
            )

            assertEquals(SyncOutcome.Success, phone.engine.sync())
            assertEquals(
                listOf(third) + shown.filter { it != third },
                api.memberOf
                    .sortedBy { it.position ?: "~" }
                    .map { it.listId },
            )
            assertEquals(
                listOf(first, second, third).sorted(),
                api.memberOf.map { it.listId }.sorted(),
            )
        }

    @Test
    fun `a drag made offline is held and drains when the network returns`() =
        runTest {
            val phone = device("device-a")
            phone.repo.createList("Inkopies")
            val braai = phone.repo.createList("Braai")
            assertEquals(SyncOutcome.Success, phone.engine.sync())

            api.online = false
            phone.repo.moveList(
                braai,
                afterId = null,
                beforeId =
                    phone.db
                        .lists()
                        .all()
                        .first()
                        .id,
            )

            // On screen already, and queued rather than lost.
            assertEquals(
                braai,
                phone.db
                    .lists()
                    .all()
                    .first()
                    .id,
            )
            assertTrue(phone.engine.sync() is SyncOutcome.Retry)
            assertTrue(
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .any { it.entityType == OutboxKind.ORDER },
            )

            api.online = true
            assertEquals(SyncOutcome.Success, phone.engine.sync())
            assertEquals(
                0,
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .size,
            )
            assertNotNull(api.memberOf.first { it.listId == braai }.position)
        }

    /** The order the server holds for this account wins on a fresh install. */
    @Test
    fun `a second device picks up the order the first one set`() =
        runTest {
            val alice = device("device-a")
            val listId = alice.repo.createList("Inkopies")
            val other = alice.repo.createList("Braai")
            assertEquals(SyncOutcome.Success, alice.engine.sync())
            alice.repo.moveList(other, afterId = null, beforeId = listId)
            assertEquals(SyncOutcome.Success, alice.engine.sync())

            val second = device("device-a2")
            assertEquals(SyncOutcome.Success, second.engine.sync())

            assertEquals(
                alice.db
                    .lists()
                    .all()
                    .map { it.id },
                second.db
                    .lists()
                    .all()
                    .map { it.id },
            )
        }

    /** A list somebody was just added to has no key, and belongs at the bottom. */
    @Test
    fun `an invited list arrives unordered and sorts last`() =
        runTest {
            val phone = device("device-a")
            val mine = phone.repo.createList("Inkopies")
            assertEquals(SyncOutcome.Success, phone.engine.sync())
            phone.repo.moveList(mine, afterId = null, beforeId = null)
            assertEquals(SyncOutcome.Success, phone.engine.sync())

            api.memberOf += Membership(listId = "list-invited", role = MembershipRole.MEMBER)
            assertEquals(SyncOutcome.Success, phone.engine.sync())

            assertEquals(
                listOf(mine, "list-invited"),
                phone.db
                    .lists()
                    .all()
                    .map { it.id },
            )
            assertNull(
                phone.db
                    .lists()
                    .find("list-invited")
                    ?.position,
            )
        }

    private fun device(id: String): DeviceStack = DeviceStack(api, id).also { devices += it }

    private companion object {
        const val LIMIT = 50
    }
}
