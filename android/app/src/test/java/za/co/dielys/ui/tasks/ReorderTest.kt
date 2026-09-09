package za.co.dielys.ui.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The arithmetic behind a drag. The gesture itself needs a screen and is not
 * tested here (H2 asks for no Compose tests), but what it computes is not a
 * drawing question: the two ids either side of the drop become the arguments to
 * `Position.between`, and an off-by-one puts the task somewhere else on both
 * phones.
 */
class ReorderTest {
    private val list = listOf("a", "b", "c", "d")

    @Test
    fun `moving down closes the gap behind the item`() {
        assertEquals(listOf("b", "c", "a", "d"), list.moved(0, 2))
    }

    @Test
    fun `moving up pushes the others down`() {
        assertEquals(listOf("a", "d", "b", "c"), list.moved(3, 1))
    }

    @Test
    fun `moving to the ends works, because both are a null neighbour`() {
        assertEquals(listOf("d", "a", "b", "c"), list.moved(3, 0))
        assertEquals(listOf("b", "c", "d", "a"), list.moved(0, 3))
    }

    @Test
    fun `a move that goes nowhere changes nothing`() {
        assertEquals(list, list.moved(2, 2))
    }

    /**
     * A drag can outrun the layout by a frame. Returning the list untouched is the
     * only safe answer — the alternative is an exception inside a gesture handler.
     */
    @Test
    fun `an index off the end is ignored rather than thrown`() {
        assertEquals(list, list.moved(0, 9))
        assertEquals(list, list.moved(-1, 1))
    }

    @Test
    fun `the neighbours of a dropped item are what the repository is told`() {
        val order = list.moved(0, 2)
        val index = order.indexOf("a")
        assertEquals("c", order.getOrNull(index - 1))
        assertEquals("d", order.getOrNull(index + 1))
    }

    @Test
    fun `the dragged order is kept while the database is still behind`() {
        assertTrue(draftStillWanted(listOf("b", "a"), listOf("a", "b")))
    }

    @Test
    fun `the dragged order is dropped once the database agrees`() {
        assertFalse(draftStillWanted(listOf("b", "a"), listOf("b", "a")))
    }

    /**
     * The other phone added something, or a sync landed. The draft was never about
     * these rows, so holding on to it would hide the new one.
     */
    @Test
    fun `the dragged order is dropped when the rows themselves changed`() {
        assertFalse(draftStillWanted(listOf("b", "a"), listOf("a", "b", "c")))
    }
}
