package za.co.dielys.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Naming a task change for a notification (ADR 0012). The one rule that is easy
 * to get wrong is what is *not* news: a move, a change to something already
 * gone, and a task that came and went before this phone saw it.
 */
class TaskActivityTest {
    private val milk = TaskFacts(title = "Milk", done = false, starred = false, deleted = false)

    @Test
    fun `a task this phone never had is an add`() {
        assertEquals(TaskActivityKind.ADDED, classifyTaskChange(null, milk))
    }

    @Test
    fun `a task created and deleted before this phone saw it is nothing`() {
        assertNull(classifyTaskChange(null, milk.copy(deleted = true)))
    }

    @Test
    fun `ticking and unticking are both a check`() {
        assertEquals(TaskActivityKind.CHECKED, classifyTaskChange(milk, milk.copy(done = true)))
        assertEquals(
            TaskActivityKind.CHECKED,
            classifyTaskChange(milk.copy(done = true), milk),
        )
    }

    @Test
    fun `a rename or a star is an update`() {
        assertEquals(
            TaskActivityKind.UPDATED,
            classifyTaskChange(milk, milk.copy(title = "Oat milk")),
        )
        assertEquals(TaskActivityKind.UPDATED, classifyTaskChange(milk, milk.copy(starred = true)))
    }

    @Test
    fun `a delete outranks everything else the same change did`() {
        val gone = milk.copy(title = "Oat milk", done = true, deleted = true)
        assertEquals(TaskActivityKind.DELETED, classifyTaskChange(milk, gone))
    }

    @Test
    fun `a tick outranks a rename in the same change`() {
        assertEquals(
            TaskActivityKind.CHECKED,
            classifyTaskChange(milk, milk.copy(title = "Oat milk", done = true)),
        )
    }

    @Test
    fun `a move changes none of the facts, so it is nothing`() {
        assertNull(classifyTaskChange(milk, milk))
    }

    @Test
    fun `anything done to a task already deleted here is nothing (F5_3)`() {
        val gone = milk.copy(deleted = true)
        assertNull(classifyTaskChange(gone, gone.copy(title = "Oat milk")))
    }
}
