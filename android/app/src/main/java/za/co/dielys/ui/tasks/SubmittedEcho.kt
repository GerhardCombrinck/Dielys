package za.co.dielys.ui.tasks

import androidx.compose.ui.text.input.TextFieldValue

/**
 * Tells the keyboard repeating what was just submitted apart from typing.
 *
 * Submitting clears the field. Pressing the keyboard's own Done key does that
 * from inside the keyboard's call into the app, and a keyboard still holding
 * the word it was composing (autocorrect's underlined text) can then hand it
 * straight back — the field refills with the item that was just added, a
 * placeholder row for it stays on screen, and pressing Done again adds it a
 * second time. The round button never trips this: tapping it is not a call
 * from the keyboard.
 *
 * Only the exact text just submitted, arriving while the field is empty, is
 * dropped. Typing anything else — including that same word letter by letter,
 * or after something else was typed — goes through, so adding "Milk" twice on
 * purpose still works.
 */
internal class SubmittedEcho {
    private var submitted: String? = null

    /** Call as the field is submitted, with what was in it. */
    fun submitted(trimmed: String) {
        if (trimmed.isNotEmpty()) submitted = trimmed
    }

    /** Call when the field is abandoned rather than submitted. */
    fun forget() {
        submitted = null
    }

    /** What the field should hold after [incoming] arrives from the keyboard. */
    fun next(
        incoming: TextFieldValue,
        current: TextFieldValue,
    ): TextFieldValue {
        val echo = current.text.isEmpty() && incoming.text.trim() == submitted
        if (echo) return current
        // An empty change is the keyboard settling after the clear, not typing.
        if (incoming.text.isNotEmpty()) submitted = null
        return incoming
    }
}
