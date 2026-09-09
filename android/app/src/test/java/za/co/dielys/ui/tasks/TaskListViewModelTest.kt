package za.co.dielys.ui.tasks

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack
import za.co.dielys.data.sync.FakeSyncApi

/**
 * The list screen against a real database. Ticking, starring and dragging all go
 * the same way: write locally, queue a mutation, let the screen follow Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TaskListViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val api = FakeSyncApi()
    private lateinit var phone: DeviceStack
    private lateinit var viewModel: TaskListViewModel
    private lateinit var listId: String

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        phone = DeviceStack(api, "device-a")
        viewModel = TaskListViewModel(phone.repo)
    }

    @After
    fun tearDown() {
        phone.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a ticked task drops to the done section without leaving the list`() =
        runTest(dispatcher) {
            open()
            val milk = phone.repo.addTask(listId, "Milk")
            phone.repo.addTask(listId, "Bread")

            viewModel.board.test {
                // `stateIn` hands the screen its initial value before Room has
                // answered — the empty frame a real screen paints for an instant.
                assertTrue(awaitItem().isEmpty)

                val start = awaitItem()
                assertEquals(listOf("Milk", "Bread"), start.active.map { it.title })
                assertEquals(emptyList<String>(), start.done.map { it.title })

                viewModel.setDone(milk, true)

                val after = awaitItem()
                assertEquals(listOf("Bread"), after.active.map { it.title })
                assertEquals(listOf("Milk"), after.done.map { it.title })
            }
        }

    /**
     * A star is a mark, not a sort. If it moved the row, the position the user
     * dragged it to would stop meaning anything — and position is the one thing
     * both phones have to agree on (F5.5).
     */
    @Test
    fun `starring a task does not move it`() =
        runTest(dispatcher) {
            open()
            phone.repo.addTask(listId, "Milk")
            val bread = phone.repo.addTask(listId, "Bread")

            viewModel.board.test {
                assertTrue(awaitItem().isEmpty)
                assertEquals(listOf("Milk", "Bread"), awaitItem().active.map { it.title })
                viewModel.setStarred(bread, true)

                val after = awaitItem().active
                assertEquals(listOf("Milk", "Bread"), after.map { it.title })
                assertTrue(after.last().starred)
            }
        }

    @Test
    fun `adding before a list is open does nothing`() =
        runTest(dispatcher) {
            viewModel.add("Milk")
            assertEquals(
                0,
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .size,
            )
        }

    @Test
    fun `a deleted task is a tombstone, not a missing row`() =
        runTest(dispatcher) {
            open()
            val milk = phone.repo.addTask(listId, "Milk")

            viewModel.board.test {
                assertTrue(awaitItem().isEmpty)
                assertEquals(listOf("Milk"), awaitItem().active.map { it.title })
                viewModel.delete(milk)
                assertTrue(awaitItem().isEmpty)
            }

            assertNotNull(
                phone.db
                    .tasks()
                    .find(milk)
                    ?.deletedAt,
            )
        }

    /**
     * The whole reorder path, end to end: the drag produces an order, the two
     * neighbours either side of the dropped row are read out of it, and exactly
     * one row is rewritten (F5.5).
     */
    @Test
    fun `a drag rewrites one row and the list comes back in the new order`() =
        runTest(dispatcher) {
            open()
            val milk = phone.repo.addTask(listId, "Milk")
            val bread = phone.repo.addTask(listId, "Bread")
            val jam = phone.repo.addTask(listId, "Jam")
            val queuedBefore =
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .size

            viewModel.board.test {
                assertTrue(awaitItem().isEmpty)

                val shown = awaitItem().active
                assertEquals(listOf(milk, bread, jam), shown.map { it.id })

                // Milk dragged to the bottom.
                val dropped = shown.moved(0, 2)
                val index = dropped.indexOfFirst { it.id == milk }
                viewModel.move(
                    milk,
                    dropped.getOrNull(index - 1)?.id,
                    dropped.getOrNull(index + 1)?.id,
                )

                assertEquals(listOf(bread, jam, milk), awaitItem().active.map { it.id })
            }

            assertEquals(
                1,
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .size - queuedBefore,
            )
        }

    private suspend fun open() {
        listId = phone.repo.createList("Groceries")
        viewModel.open(listId)
    }

    private companion object {
        const val LIMIT = 20
    }
}
