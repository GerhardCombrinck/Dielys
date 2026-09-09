package za.co.dielys.ui.lists

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
import za.co.dielys.data.sync.FakeSyncApi

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
        viewModel = ListsViewModel(phone.repo)
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
                assertEquals(emptyList<String>(), awaitItem().map { it.title })
                viewModel.create("  Groceries  ")

                val shown = awaitItem()
                assertEquals(listOf("Groceries"), shown.map { it.title })
                // No server timestamp yet: the row is an optimistic local write,
                // which is what the screen labels "Not synced yet".
                assertEquals(null, shown.single().updatedAt)
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
                assertEquals(emptyList<String>(), awaitItem().map { it.title })
                assertEquals(listOf("Braai"), awaitItem().map { it.title })
                viewModel.delete(id)
                assertEquals(emptyList<String>(), awaitItem().map { it.title })
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

    private companion object {
        const val LIMIT = 20
    }
}
