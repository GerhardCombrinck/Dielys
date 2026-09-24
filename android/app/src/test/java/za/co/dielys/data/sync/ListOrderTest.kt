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
import za.co.dielys.data.local.ListEntity
import za.co.dielys.data.remote.ListChange
import za.co.dielys.data.remote.Membership
import za.co.dielys.data.remote.MembershipRole
import za.co.dielys.data.remote.TaskList

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

    /**
     * #68: the changelog does not carry the order, so a rename coming back from
     * the server must not wipe it. It did, and the renamed list dropped to the
     * bottom until the next membership fetch put it back.
     */
    @Test
    fun `a list change from the server keeps this account's order and member count`() =
        runTest {
            val phone = device("device-a")
            // Straight into Room, not through the outbox: a pending edit of its
            // own would shadow the change below and nothing would be written.
            val braai = "list-braai"
            phone.db.lists().upsert(
                ListEntity(
                    id = braai,
                    title = "Braai",
                    role = "owner",
                    position = "V",
                    memberCount = 2,
                ),
            )

            phone.applier.apply(
                ListChange(
                    seq = 1,
                    listId = braai,
                    idempotencyKey = "rename-echo",
                    deviceId = "device-b",
                    serverTimestamp = STAMP,
                    entity = TaskList(id = braai, title = "Braaivleis", updatedAt = STAMP),
                ),
            )

            val list = checkNotNull(phone.db.lists().find(braai))
            assertEquals("Braaivleis", list.title)
            assertEquals("V", list.position)
            assertEquals(2, list.memberCount)
            assertEquals("owner", list.role)
        }

    /**
     * A queued drag is filed under the list's id, but it is not an edit to the
     * list. Counting it as one skipped the other member's rename — and the
     * cursor moved past it, so it never came back.
     */
    @Test
    fun `a drag still queued does not hide somebody else's rename`() =
        runTest {
            val phone = device("device-a")
            val braai = phone.repo.createList("Braai")
            assertEquals(SyncOutcome.Success, phone.engine.sync())
            phone.repo.moveList(braai, afterId = null, beforeId = null)
            assertTrue(
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .any { it.entityType == OutboxKind.ORDER },
            )

            val cursor =
                phone.db
                    .syncState()
                    .find(braai)
                    ?.cursor ?: 0L
            assertEquals(
                ApplyOutcome.APPLIED,
                phone.applier.apply(
                    ListChange(
                        seq = cursor + 1,
                        listId = braai,
                        idempotencyKey = "rename-by-b",
                        deviceId = "device-b",
                        serverTimestamp = STAMP,
                        entity = TaskList(id = braai, title = "Braaivleis", updatedAt = STAMP),
                    ),
                ),
            )

            assertEquals(
                "Braaivleis",
                phone.db
                    .lists()
                    .find(braai)
                    ?.title,
            )
        }

    private fun device(id: String): DeviceStack = DeviceStack(api, id).also { devices += it }

    private companion object {
        const val LIMIT = 50
        const val STAMP = "2026-09-14T12:00:00.000Z"
    }
}
