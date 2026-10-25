package za.co.dielys.ui.tasks

import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class SubmittedEchoTest {
    private val empty = TextFieldValue()

    @Test
    fun `the keyboard handing back what was just submitted is dropped`() {
        val echo = SubmittedEcho().apply { submitted("Beskuit") }

        assertEquals(empty, echo.next(TextFieldValue("Beskuit"), empty))
    }

    @Test
    fun `trailing space on the echoed text still counts as the same text`() {
        val echo = SubmittedEcho().apply { submitted("Beskuit") }

        assertEquals(empty, echo.next(TextFieldValue("Beskuit "), empty))
    }

    @Test
    fun `nothing submitted yet means nothing to drop`() {
        val incoming = TextFieldValue("Beskuit")

        assertEquals(incoming, SubmittedEcho().next(incoming, empty))
    }

    @Test
    fun `different text is typing, not an echo`() {
        val echo = SubmittedEcho().apply { submitted("Beskuit") }
        val typed = TextFieldValue("B")

        assertEquals(typed, echo.next(typed, empty))
    }

    @Test
    fun `the same word typed again after something else is accepted`() {
        val echo = SubmittedEcho().apply { submitted("Beskuit") }
        echo.next(TextFieldValue("B"), empty)

        val again = TextFieldValue("Beskuit")
        assertEquals(again, echo.next(again, empty))
    }

    @Test
    fun `an empty change before the echo does not forget what was submitted`() {
        val echo = SubmittedEcho().apply { submitted("Beskuit") }
        echo.next(TextFieldValue(""), empty)

        assertEquals(empty, echo.next(TextFieldValue("Beskuit"), empty))
    }

    @Test
    fun `the same text is accepted once the field already has something in it`() {
        val echo = SubmittedEcho().apply { submitted("Beskuit") }
        val incoming = TextFieldValue("Beskuit")

        assertEquals(incoming, echo.next(incoming, TextFieldValue("Bes")))
    }

    @Test
    fun `a field abandoned instead of submitted drops nothing`() {
        val echo = SubmittedEcho().apply { submitted("Beskuit") }
        echo.forget()
        val incoming = TextFieldValue("Beskuit")

        assertEquals(incoming, echo.next(incoming, empty))
    }

    @Test
    fun `submitting an empty field keeps what was remembered`() {
        val echo = SubmittedEcho().apply { submitted("Beskuit") }
        echo.submitted("")

        assertEquals(empty, echo.next(TextFieldValue("Beskuit"), empty))
    }
}
