package za.co.dielys.ui.lists

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack
import za.co.dielys.data.local.AndroidStringProvider
import za.co.dielys.data.local.ListEntity
import za.co.dielys.data.sync.FakeSyncApi
import za.co.dielys.domain.InviteLink

/**
 * The lists screen sees what Room holds and nothing else (E1.2), so these run the
 * view model against a real database and a real outbox and watch the flows the
 * screen collects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ListsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val api = FakeSyncApi()
    private lateinit var phone: DeviceStack
    private lateinit var viewModel: ListsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        phone = DeviceStack(api, "device-a")
        viewModel =
            ListsViewModel(
                phone.repo,
                phone.accents,
                phone.sharing,
                phone.invites,
                AndroidStringProvider(ApplicationProvider.getApplicationContext()),
            )
    }

    @After
    fun tearDown() {
        phone.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a new list is on screen before the server has heard of it`() =
        runTest(dispatcher) {
            api.online = false

            viewModel.lists.test {
                assertEquals(emptyList<String>(), awaitItem().map { it.list.title })
                viewModel.create("  Groceries  ")

                val shown = awaitItem()
                assertEquals(listOf("Groceries"), shown.map { it.list.title })
                // No server timestamp yet: the row is an optimistic local write,
                // which is what the screen labels "Not synced yet".
                assertEquals(null, shown.single().list.updatedAt)
            }

            // The claim and the rename, both queued with the entity (F5.7).
            assertEquals(
                2,
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .size,
            )
        }

    @Test
    fun `a blank name is not a list`() =
        runTest(dispatcher) {
            viewModel.create("   ")
            assertEquals(
                0,
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .size,
            )
            assertEquals(emptyList<String>(), phone.db.lists().knownIds())
        }

    @Test
    fun `a deleted list leaves the screen but not the database`() =
        runTest(dispatcher) {
            val id = phone.repo.createList("Braai")

            viewModel.lists.test {
                // `stateIn` hands the screen its initial value before Room has
                // answered — the empty frame a real screen paints for an instant.
                assertEquals(emptyList<String>(), awaitItem().map { it.list.title })
                assertEquals(listOf("Braai"), awaitItem().map { it.list.title })
                viewModel.delete(id)
                assertEquals(emptyList<String>(), awaitItem().map { it.list.title })
            }

            // F5.3: a delete is a tombstone. The row is still there, still known
            // to catch-up, and it can never be resurrected by a late update.
            assertNotNull(
                phone.db
                    .lists()
                    .find(id)
                    ?.deletedAt,
            )
        }

    @Test
    fun `the pending count is whatever the outbox is still holding`() =
        runTest(dispatcher) {
            api.online = false

            viewModel.pending.test {
                assertEquals(0, awaitItem())
                phone.repo.createList("Groceries")
                assertEquals(2, awaitItem())
            }
        }

    @Test
    fun `opening the invite dialog asks who the list is for, before touching the server`() =
        runTest(dispatcher) {
            viewModel.invite(owned("list-1", "Groceries"))

            val entering = viewModel.invite.value as InviteState.EnteringEmail
            assertEquals("list-1", entering.listId)
            assertEquals("Groceries", entering.listTitle)
        }

    @Test
    fun `sharing a list mails the invite to the address typed in`() =
        runTest(dispatcher) {
            viewModel.sendInvite("list-1", "Groceries", "guest@dielys.test")

            val sent = viewModel.invite.value as InviteState.Sent
            assertEquals("Groceries", sent.listTitle)
            assertEquals("guest@dielys.test", sent.email)
            assertEquals("guest@dielys.test", api.inviteEmail)
            assertEquals("Groceries", api.inviteListTitle)
        }

    /** L3: the screen hides the option, and the server refuses it anyway. */
    @Test
    fun `a list somebody else shared cannot be invited to`() =
        runTest(dispatcher) {
            api.refuseInviteAsNotOwner()

            viewModel.sendInvite("list-1", "Groceries", "guest@dielys.test")

            val failed = viewModel.invite.value as InviteState.Failed
            assertEquals("Only the person who made this list can share it.", failed.message)
        }

    @Test
    fun `no connection is not a dead invite`() =
        runTest(dispatcher) {
            api.online = false

            viewModel.sendInvite("list-1", "Groceries", "guest@dielys.test")

            val failed = viewModel.invite.value as InviteState.Failed
            assertEquals("No connection. Try again when you have signal.", failed.message)
        }

    @Test
    fun `a blank address does not send anything`() =
        runTest(dispatcher) {
            viewModel.invite(owned("list-1", "Groceries"))
            viewModel.sendInvite("list-1", "Groceries", "   ")

            assertEquals(null, api.inviteEmail)
            assertEquals(true, viewModel.invite.value is InviteState.EnteringEmail)
        }

    @Test
    fun `a pasted message with a link in it joins the list`() =
        runTest(dispatcher) {
            api.inviteToken = "aaa.bbb.ccc"
            api.inviteFor = "list-from-the-other-phone"

            viewModel.join("Join \"Braai\" on Dielys: ${InviteLink.url("aaa.bbb.ccc")}")

            assertEquals("Joined. The list will appear in a moment.", viewModel.joined.value)
            // H3.12: the list is on the server, not here, so the join asks for a
            // sync rather than leaving it to the half-hourly worker.
            assertEquals(1, phone.scheduler.requests)
        }

    @Test
    fun `accepting the same invite twice is a no-op, not an error`() =
        runTest(dispatcher) {
            api.inviteToken = "aaa.bbb.ccc"
            api.inviteFor = "list-from-the-other-phone"

            viewModel.join(InviteLink.url("aaa.bbb.ccc"))
            viewModel.dismissJoined()
            viewModel.join(InviteLink.url("aaa.bbb.ccc"))

            assertEquals("You are already on that list.", viewModel.joined.value)
        }

    @Test
    fun `an expired invite says to ask for a new one`() =
        runTest(dispatcher) {
            api.inviteFor = "list-from-the-other-phone"

            viewModel.join(InviteLink.url("some.other.token"))

            assertEquals("That invite has expired. Ask for a new one.", viewModel.joined.value)
        }

    /** L3: holding the link is not enough — it must be the invited address. */
    @Test
    fun `an invite for a different account says so, not that it joined`() =
        runTest(dispatcher) {
            api.inviteToken = "aaa.bbb.ccc"
            api.inviteFor = "list-from-the-other-phone"
            api.refuseAcceptAsWrongRecipient()

            viewModel.join(InviteLink.url("aaa.bbb.ccc"))

            assertEquals(
                "This invite was sent to a different email than the one you're signed in with.",
                viewModel.joined.value,
            )
        }

    @Test
    fun `text with no invite in it never reaches the server`() =
        runTest(dispatcher) {
            viewModel.join("see you saturday")

            assertEquals("That does not look like an invite.", viewModel.joined.value)
            assertEquals(null, api.inviteFor)
        }

    /**
     * A link is something a stranger can send, so a tapped one is offered and
     * never acted on until the person on the phone says so.
     */
    @Test
    fun `a tapped link waits to be accepted`() =
        runTest(dispatcher) {
            api.inviteToken = "aaa.bbb.ccc"
            api.inviteFor = "list-from-the-other-phone"

            phone.invites.offer(InviteLink.url("aaa.bbb.ccc"))
            assertNotNull(viewModel.invitation.value)
            assertEquals(null, viewModel.joined.value)

            viewModel.acceptInvitation()

            assertEquals("Joined. The list will appear in a moment.", viewModel.joined.value)
            assertEquals(null, viewModel.invitation.value)
        }

    @Test
    fun `a declined link is gone rather than asked about again`() =
        runTest(dispatcher) {
            api.inviteFor = "list-from-the-other-phone"
            phone.invites.offer(InviteLink.url("aaa.bbb.ccc"))

            viewModel.declineInvitation()

            assertEquals(null, viewModel.invitation.value)
            assertEquals(null, viewModel.joined.value)
        }

    /**
     * A row as the screen would have it, built rather than read: reading Room
     * first would resume this test off its own dispatcher, and the view model's
     * work would then be queued behind an assertion instead of done before it.
     */
    private fun owned(
        id: String,
        title: String,
    ): ListEntity = ListEntity(id = id, title = title, role = "owner")

    private companion object {
        const val LIMIT = 20
    }
}
