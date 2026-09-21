package za.co.dielys.ui.tasks

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack
import za.co.dielys.data.local.DoneSectionPrefs
import za.co.dielys.data.local.NewTaskPlacement
import za.co.dielys.data.sync.FakeSyncApi
import za.co.dielys.ui.reorder.moved

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
        val placement =
            object : NewTaskPlacement {
                override val newItemsOnTop = MutableStateFlow(true)
            }
        val doneSection =
            object : DoneSectionPrefs {
                override fun isExpanded(listId: String) = true

                override fun setExpanded(
                    listId: String,
                    expanded: Boolean,
                ) = Unit
            }
        viewModel =
            TaskListViewModel(phone.repo, phone.accents, phone.clock, doneSection, placement)
    }

    @After
    fun tearDown() {
        // Everything the view model started is over before the database closes and
        // `Dispatchers.Main` is put back, or a Room query still in flight resumes
        // into whatever the next test has made Main. Cancelling is not enough:
        // the coroutine still has to come back to notice.
        runBlocking {
            viewModel.viewModelScope.coroutineContext.job
                .cancelAndJoin()
        }
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
                // Newest first: an added task goes to the top.
                assertEquals(listOf("Bread", "Milk"), start.active.map { it.title })
                assertEquals(emptyList<String>(), start.done.map { it.title })

                viewModel.setDone(milk, true)

                val after = awaitItem()
                assertEquals(listOf("Bread"), after.active.map { it.title })
                assertEquals(listOf("Milk"), after.done.map { it.title })
            }
        }

    /**
     * The frame before Room answers and a list with nothing in it are both empty,
     * and only one of them is allowed to say so (#65).
     */
    @Test
    fun `an empty list is loaded, and the frame before it is not`() =
        runTest(dispatcher) {
            open()

            viewModel.board.test {
                val before = awaitItem()
                assertTrue(before.isEmpty)
                assertFalse(before.loaded)

                val answered = awaitItem()
                assertTrue(answered.isEmpty)
                assertTrue(answered.loaded)
            }
        }

    /**
     * A star says "this one first", so it moves the row to the top and writes
     * the position with it — one patch, so no device ever sees the star without
     * the move.
     */
    @Test
    fun `starring a task moves it to the top`() =
        runTest(dispatcher) {
            open()
            val milk = phone.repo.addTask(listId, "Milk")
            phone.repo.addTask(listId, "Bread")

            viewModel.board.test {
                assertTrue(awaitItem().isEmpty)
                assertEquals(listOf("Bread", "Milk"), awaitItem().active.map { it.title })
                viewModel.setStarred(milk, true)

                val after = awaitItem().active
                assertEquals(listOf("Milk", "Bread"), after.map { it.title })
                assertTrue(after.first().starred)
            }
        }

    /**
     * Unstarring leaves the row where it is. Sending it back down would mean
     * remembering where it came from, and where an unstarred item belongs is
     * wherever the person put it.
     */
    @Test
    fun `unstarring leaves the task where it is`() =
        runTest(dispatcher) {
            open()
            val milk = phone.repo.addTask(listId, "Milk")
            phone.repo.addTask(listId, "Bread")
            phone.repo.setStarred(milk, true)

            viewModel.board.test {
                assertTrue(awaitItem().isEmpty)
                assertEquals(listOf("Milk", "Bread"), awaitItem().active.map { it.title })
                viewModel.setStarred(milk, false)

                val after = awaitItem().active
                assertEquals(listOf("Milk", "Bread"), after.map { it.title })
                assertFalse(after.first().starred)
            }
        }

    /**
     * #38: the Done section is permanently reverse-chronological. Each
     * completion promotes the row above every earlier one, the same
     * fractional-index trick [setStarred] uses, scoped to the done rows.
     */
    @Test
    fun `completing tasks stacks the done section most-recent first`() =
        runTest(dispatcher) {
            open()
            val milk = phone.repo.addTask(listId, "Milk")
            val bread = phone.repo.addTask(listId, "Bread")
            val jam = phone.repo.addTask(listId, "Jam")

            viewModel.board.test {
                assertTrue(awaitItem().isEmpty)
                awaitItem()

                viewModel.setDone(milk, true)
                assertEquals(listOf("Milk"), awaitItem().done.map { it.title })

                viewModel.setDone(bread, true)
                assertEquals(listOf("Bread", "Milk"), awaitItem().done.map { it.title })

                viewModel.setDone(jam, true)
                assertEquals(listOf("Jam", "Bread", "Milk"), awaitItem().done.map { it.title })
            }
        }

    /**
     * Un-completing leaves the row's position exactly where completing it put
     * it — same reasoning as unstarring: no attempt to recall where it was
     * before.
     */
    @Test
    fun `un-completing a task leaves its position untouched`() =
        runTest(dispatcher) {
            open()
            val milk = phone.repo.addTask(listId, "Milk")
            val bread = phone.repo.addTask(listId, "Bread")
            phone.repo.setDone(milk, true)
            phone.repo.setDone(bread, true)
            val breadPosition =
                phone.db
                    .tasks()
                    .find(bread)
                    ?.position

            viewModel.board.test {
                assertTrue(awaitItem().isEmpty)
                assertEquals(listOf("Bread", "Milk"), awaitItem().done.map { it.title })

                viewModel.setDone(bread, false)
                assertEquals(listOf("Milk"), awaitItem().done.map { it.title })
            }

            assertEquals(
                breadPosition,
                phone.db
                    .tasks()
                    .find(bread)
                    ?.position,
            )
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
                // Newest first, so the screen reads Jam, Bread, Milk.
                assertEquals(listOf(jam, bread, milk), shown.map { it.id })

                // Jam dragged to the bottom.
                val dropped = shown.moved(0, 2)
                val index = dropped.indexOfFirst { it.id == jam }
                viewModel.move(
                    jam,
                    dropped.getOrNull(index - 1)?.id,
                    dropped.getOrNull(index + 1)?.id,
                )

                assertEquals(listOf(bread, milk, jam), awaitItem().active.map { it.id })
            }

            assertEquals(
                1,
                phone.db
                    .outbox()
                    .pending(LIMIT)
                    .size - queuedBefore,
            )
        }

    /**
     * Renaming is no longer a dialog but the add bar with a row loaded into it
     * (#63), which makes this the path every edit now takes.
     */
    @Test
    fun `renaming a task writes the new title and nothing else`() =
        runTest(dispatcher) {
            open()
            val milk = phone.repo.addTask(listId, "Mlik")
            val before = phone.db.tasks().find(milk)

            viewModel.board.test {
                assertTrue(awaitItem().isEmpty)
                assertEquals(listOf("Mlik"), awaitItem().active.map { it.title })

                viewModel.rename(milk, "  Milk  ")

                assertEquals(listOf("Milk"), awaitItem().active.map { it.title })
            }

            val after = phone.db.tasks().find(milk)
            assertEquals(before?.position, after?.position)
            assertEquals(before?.starred, after?.starred)
        }

    /**
     * What the bar leans on when the field is cleared and saved: an empty title
     * is not a request for a nameless row (#63).
     */
    @Test
    fun `renaming to nothing leaves the title alone`() =
        runTest(dispatcher) {
            open()
            val milk = phone.repo.addTask(listId, "Milk")

            viewModel.board.test {
                assertTrue(awaitItem().isEmpty)
                assertEquals(listOf("Milk"), awaitItem().active.map { it.title })

                viewModel.rename(milk, "   ")

                expectNoEvents()
            }

            assertEquals(
                "Milk",
                phone.db
                    .tasks()
                    .find(milk)
                    ?.title,
            )
        }

    /**
     * #62. A star is the household saying "this one first"; the next thing
     * typed is not a reason to demote it.
     */
    @Test
    fun `a new item goes in under the starred ones, not above them`() =
        runTest(dispatcher) {
            open()
            phone.repo.addTask(listId, "Milk")
            val bread = phone.repo.addTask(listId, "Bread")
            phone.repo.setStarred(bread, true)

            phone.repo.addTask(listId, "Jam")

            assertEquals(
                listOf("Bread", "Jam", "Milk"),
                phone.db
                    .tasks()
                    .inList(listId)
                    .map { it.title },
            )
        }

    /**
     * Only the run of stars at the top counts. A starred row dragged down is
     * somebody overruling the star, and the rows they put above it there must
     * not be leapfrogged by the next thing typed (#62).
     */
    @Test
    fun `a starred row dragged down does not drag new items past it`() =
        runTest(dispatcher) {
            open()
            val milk = phone.repo.addTask(listId, "Milk")
            val bread = phone.repo.addTask(listId, "Bread")
            phone.repo.setStarred(bread, true)
            // Bread is at the top; put it back under Milk by hand.
            phone.repo.moveTask(bread, milk, null)

            phone.repo.addTask(listId, "Jam")

            assertEquals(
                listOf("Jam", "Milk", "Bread"),
                phone.db
                    .tasks()
                    .inList(listId)
                    .map { it.title },
            )
        }

    /** Nothing starred is the plain case this grew out of: straight to the top. */
    @Test
    fun `with nothing starred a new item is still first`() =
        runTest(dispatcher) {
            open()
            phone.repo.addTask(listId, "Milk")

            phone.repo.addTask(listId, "Bread")

            assertEquals(
                listOf("Bread", "Milk"),
                phone.db
                    .tasks()
                    .inList(listId)
                    .map { it.title },
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
