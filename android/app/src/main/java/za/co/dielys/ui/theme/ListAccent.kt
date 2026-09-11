package za.co.dielys.ui.theme

import androidx.compose.ui.graphics.Color

/** Cycles across lists — Room has no per-list color, so the identity is purely visual. */
private val ListAccents =
    listOf(
        Color(0xFFE8A33D), // amber
        Color(0xFF7FA893), // sage
        Color(0xFF7C93C4), // dusty blue
        Color(0xFFC97B63), // terracotta
    )

/**
 * A list's colour, keyed on its id rather than its position: the dot on the
 * Lists screen and the dot on that list's own header have to agree, and the
 * task screen never learns the row index. Keying on the id also means deleting
 * one list does not recolour the ones under it.
 */
fun listAccent(listId: String): Color =
    ListAccents[Math.floorMod(listId.hashCode(), ListAccents.size)]
