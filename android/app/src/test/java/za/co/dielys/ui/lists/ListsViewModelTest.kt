package za.co.dielys.ui.lists

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack
import za.co.dielys.data.PendingListOpen
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
        // A phone that has synced before, which is most of what this screen sees.
        // The tests about the first sync after signing in take this back (#66).
        phone.sweeps.lastFullCatchUpAt = 0L
        viewModel =
            ListsViewModel(
                phone.repo,
                phone.accents,
                phone.sharing,
                phone.invites,
                PendingListOpen(),
                AndroidStringProvider(ApplicationProvider.getApplicationContext()),
                phone.sweeps,
            )
    }

    @After
    fun tearDown() {
        // Everything the view model started is over before the database closes and
        // `Dispatchers.Main` is put back. A join still waiting on Room would
        // otherwise resume from Room's own thread after that, into whatever the
        // next test has made Main — which fails that test instead of this one.
        // Cancelling is not enough: the waiter still has to come back to notice.
        runBlocking {
            viewModel.viewModelScope.coroutineContext.job
                .cancelAndJoin()
        }
        phone.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a new list is on screen before the server has heard of it`() =
        runTest(dispatcher) {
            api.online = false

            viewModel.lists.test {
                // Room's first answer, and an empty one — not the "nothing yet"
                // before it, which is null (#65).
                assertNull(awaitItem())
                assertEquals(emptyList<String>(), awaitItem()?.map { it.list.title })
                viewModel.create("  Groceries  ")

                // Already wearing its colour: the row and the colour are one
                // transaction and one query, never a row and then a colour (#57).
                val shown = awaitItem().orEmpty()
                assertEquals(listOf("Groceries"), shown.map { it.list.title })
                assertNotNull(shown.single().accent)
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
                // answered. Null, so it is drawn as loading rather than as a
                // phone with no lists (#65).
                assertNull(awaitItem())
                assertEquals(listOf("Braai"), awaitItem()?.map { it.list.title })
                viewModel.delete(id)
                assertEquals(emptyList<String>(), awaitItem()?.map { it.list.title })
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

    /**
     * #66: straight after signing in, the lists are on the server and not yet
     * here. Neither the empty-screen invitation to make a first list nor an
     * "Untitled list" for each one `/auth/memberships` announced is true, so
     * the screen keeps loading until the first sync has pulled them.
     */
    @Test
    fun `after signing in, the screen waits for the first sync rather than showing placeholders`() =
        runTest(dispatcher) {
            val otherPhone = DeviceStack(api, "device-b")
            otherPhone.repo.createList("Braai")
            otherPhone.engine.sync()
            phone.sweeps.lastFullCatchUpAt = null

            viewModel.lists.test {
                assertNull(awaitItem())

                // Known, but nameless until its changelog lands: still nothing.
                phone.engine.discoverLists()
                assertEquals(
                    listOf(""),
                    phone.db
                        .lists()
                        .observeAll()
                        .first()
                        .map { it.title },
                )
                expectNoEvents()

                phone.engine.sync()
                assertEquals(listOf("Braai"), awaitItem()?.map { it.list.title })
            }
            otherPhone.close()
        }

    @Test
    fun `a first sync that finds no lists is an empty screen, not a wait`() =
        runTest(dispatcher) {
            phone.sweeps.lastFullCatchUpAt = null

            viewModel.lists.test {
                assertNull(awaitItem())
                phone.engine.sync()
                assertEquals(emptyList<String>(), awaitItem()?.map { it.list.title })
            }
        }

    /** Making a list works offline, so a first sync stuck without signal does not
     *  keep a list made meanwhile off the screen. */
    @Test
    fun `a list made before the first sync lands is shown`() =
        runTest(dispatcher) {
            api.online = false
            phone.sweeps.lastFullCatchUpAt = null

            viewModel.lists.test {
                assertNull(awaitItem())
                viewModel.create("Groceries")
                val shown = awaitItem().orEmpty()
                assertEquals(listOf("Groceries"), shown.map { it.list.title })
                assertNotNull(shown.single().accent)
            }
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

            // #61: accepting is not arriving. The server has the membership, and
            // the screen keeps saying so until the list itself turns up.
            assertEquals(
                JoinState.Fetching("list-from-the-other-phone"),
                viewModel.join.value,
            )
            // H3.12: the list is on the server, not here, so the join asks for a
            // sync rather than leaving it to the half-hourly worker.
            assertEquals(1, phone.scheduler.requests)
        }

    /**
     * #61: being on the list already and joining it fresh end in the same
     * place — looking at the list — so they are not told apart. A phone that
     * was reinstalled is already a member and still has nothing to show.
     */
    @Test
    fun `accepting the same invite twice is a no-op, not an error`() =
        runTest(dispatcher) {
            api.inviteToken = "aaa.bbb.ccc"
            api.inviteFor = "list-from-the-other-phone"

            viewModel.join(InviteLink.url("aaa.bbb.ccc"))
            viewModel.dismissJoin()
            viewModel.join(InviteLink.url("aaa.bbb.ccc"))

            assertEquals(
                JoinState.Fetching("list-from-the-other-phone"),
                viewModel.join.value,
            )
        }

    /**
     * The spinner's whole job (#61): it ends when the list is really on screen,
     * not when the server said yes. Room getting the row is what closes it.
     */
    @Test
    fun `the wait ends when the list actually arrives`() =
        runTest(dispatcher) {
            api.inviteToken = "aaa.bbb.ccc"
            api.inviteFor = "list-from-the-other-phone"

            viewModel.join(InviteLink.url("aaa.bbb.ccc"))

            viewModel.join.test {
                assertEquals(JoinState.Fetching("list-from-the-other-phone"), awaitItem())

                // What `/auth/memberships` writes first: the list is known, but
                // it has no name yet, so there is still nothing worth showing
                // and the wait carries on.
                phone.db.lists().upsert(ListEntity(id = "list-from-the-other-phone", title = ""))
                expectNoEvents()

                // And then the changelog lands and names it.
                phone.db.lists().upsert(
                    ListEntity(id = "list-from-the-other-phone", title = "Braai"),
                )
                assertEquals(null, awaitItem())
            }
        }

    @Test
    fun `an expired invite says to ask for a new one`() =
        runTest(dispatcher) {
            api.inviteFor = "list-from-the-other-phone"

            viewModel.join(InviteLink.url("some.other.token"))

            assertEquals(
                JoinState.Failed("That invite has expired. Ask for a new one."),
                viewModel.join.value,
            )
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
                JoinState.Failed(
                    "This invite was sent to a different email address than the one you are " +
                        "signed in with.",
                ),
                viewModel.join.value,
            )
        }

    @Test
    fun `text with no invite in it never reaches the server`() =
        runTest(dispatcher) {
            viewModel.join("see you saturday")

            assertEquals(
                JoinState.Failed("That does not look like an invite."),
                viewModel.join.value,
            )
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
            assertEquals(null, viewModel.join.value)

            viewModel.acceptInvitation()

            assertEquals(
                JoinState.Fetching("list-from-the-other-phone"),
                viewModel.join.value,
            )
            assertEquals(null, viewModel.invitation.value)
        }

    @Test
    fun `a declined link is gone rather than asked about again`() =
        runTest(dispatcher) {
            api.inviteFor = "list-from-the-other-phone"
            phone.invites.offer(InviteLink.url("aaa.bbb.ccc"))

            viewModel.declineInvitation()

            assertEquals(null, viewModel.invitation.value)
            assertEquals(null, viewModel.join.value)
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
