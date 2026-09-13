package za.co.dielys.ui.lists

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack
import za.co.dielys.data.local.AndroidStringProvider
import za.co.dielys.data.sync.FakeSyncApi

/**
 * The "shared with" sheet (#60). Everything here is a live server answer rather
 * than anything Room holds, so these run against the fake API and watch the one
 * state flow the sheet collects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MembersViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val api = FakeSyncApi()
    private lateinit var phone: DeviceStack
    private lateinit var viewModel: MembersViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        phone = DeviceStack(api, "device-a")
        viewModel =
            MembersViewModel(
                phone.sharing,
                phone.account,
                AndroidStringProvider(ApplicationProvider.getApplicationContext()),
            )
    }

    @After
    fun tearDown() {
        phone.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `the sheet names everybody on the list, and knows which one is you`() =
        runTest(dispatcher) {
            api.iAmOwnerOf("list-1")

            viewModel.open("list-1")

            val loaded = viewModel.members.value as MembersState.Loaded
            assertEquals(
                listOf("device-a@dielys.test", "guest@dielys.test"),
                loaded.members.map { it.email },
            )
            assertEquals("device-a-user", loaded.meUserId)
            assertEquals(true, loaded.iAmOwner)
        }

    /**
     * The id is only stored from the server's own login answer, so a session
     * older than that has none — and matching on it would leave this account
     * unable to recognise itself and offered Remove on its own row.
     */
    @Test
    fun `a session with no stored user id still recognises itself by email`() =
        runTest(dispatcher) {
            phone.account.userId = null
            api.iAmOwnerOf("list-1")

            viewModel.open("list-1")

            val loaded = viewModel.members.value as MembersState.Loaded
            assertEquals("device-a-user", loaded.meUserId)
            assertEquals(true, loaded.iAmOwner)
        }

    @Test
    fun `a member on somebody else's list is not the owner of it`() =
        runTest(dispatcher) {
            api.guestOwns("list-1")

            viewModel.open("list-1")

            val loaded = viewModel.members.value as MembersState.Loaded
            assertEquals(false, loaded.iAmOwner)
        }

    @Test
    fun `removing somebody takes them off the sheet`() =
        runTest(dispatcher) {
            api.iAmOwnerOf("list-1")
            viewModel.open("list-1")

            viewModel.remove("list-1", "guest-user")

            assertEquals(listOf("list-1" to "guest-user"), api.removedMembers)
            val loaded = viewModel.members.value as MembersState.Loaded
            assertEquals(listOf("device-a@dielys.test"), loaded.members.map { it.email })
        }

    /** Leaving takes the sheet with it — there is no list left to be shown
     *  the members of. */
    @Test
    fun `leaving closes the sheet rather than reloading it`() =
        runTest(dispatcher) {
            api.guestOwns("list-1")
            viewModel.open("list-1")

            viewModel.remove("list-1", "device-a-user")

            assertEquals(null, viewModel.members.value)
        }

    /**
     * Only reachable if the membership changed underneath — the sheet offers
     * nothing the server would refuse — so it shows what is true now rather
     * than explaining a refusal nobody asked for.
     */
    @Test
    fun `a refused removal reloads instead of reporting itself`() =
        runTest(dispatcher) {
            api.iAmOwnerOf("list-1")
            viewModel.open("list-1")
            api.refuseRemoveAsNotAllowed()

            viewModel.remove("list-1", "guest-user")

            val loaded = viewModel.members.value as MembersState.Loaded
            assertEquals(
                listOf("device-a@dielys.test", "guest@dielys.test"),
                loaded.members.map { it.email },
            )
        }

    @Test
    fun `no connection says so rather than showing an empty list`() =
        runTest(dispatcher) {
            api.online = false

            viewModel.open("list-1")

            val failed = viewModel.members.value as MembersState.Failed
            assertEquals(
                "Could not check who this is shared with: " +
                    "No connection. Try again when you have signal.",
                failed.message,
            )
        }

    /** Off the list already — somebody removed this account while the sheet was
     *  on its way. Nothing to show, and the next sync takes the row too. */
    @Test
    fun `a list that is no longer ours closes the sheet`() =
        runTest(dispatcher) {
            api.refuseMembersAsNotOurs()

            viewModel.open("list-1")

            assertEquals(null, viewModel.members.value)
        }

    /**
     * A member takes a list off their phone from its own menu now that deleting
     * it is the owner's call (ADR 0006) — and nothing opens when that works.
     */
    @Test
    fun `leaving from the menu takes this account off the list and opens nothing`() =
        runTest(dispatcher) {
            api.guestOwns("list-1")

            viewModel.leave("list-1")

            assertEquals(listOf("list-1" to "device-a-user"), api.removedMembers)
            assertEquals(null, viewModel.members.value)
        }

    @Test
    fun `leaving with no stored user id finds this account by email first`() =
        runTest(dispatcher) {
            phone.account.userId = null
            api.guestOwns("list-1")

            viewModel.leave("list-1")

            assertEquals(listOf("list-1" to "device-a-user"), api.removedMembers)
            assertEquals(null, viewModel.members.value)
        }

    @Test
    fun `a leave that could not be sent says why, as a leave`() =
        runTest(dispatcher) {
            api.guestOwns("list-1")
            api.online = false

            viewModel.leave("list-1")

            val failed = viewModel.members.value as MembersState.Failed
            assertEquals(
                "Could not leave this list: No connection. Try again when you have signal.",
                failed.message,
            )
        }

    /** This account's list, with one guest on it. */
    private fun FakeSyncApi.iAmOwnerOf(listId: String) {
        addMember(listId, "device-a-user", "device-a@dielys.test", isOwner = true)
        addMember(listId, "guest-user", "guest@dielys.test")
    }

    /** Somebody else's list, with this account invited onto it. */
    private fun FakeSyncApi.guestOwns(listId: String) {
        addMember(listId, "guest-user", "guest@dielys.test", isOwner = true)
        addMember(listId, "device-a-user", "device-a@dielys.test")
    }
}
