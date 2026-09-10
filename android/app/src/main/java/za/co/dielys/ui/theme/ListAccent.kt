package za.co.dielys.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Cycles across lists — Room has no per-list color, so the identity is purely visual. */
private val ListAccents =
    listOf(
        Color(0xFFE8A33D), // amber
        Color(0xFF7FA893), // sage
        Color(0xFF7C93C4), // dusty blue
        Color(0xFFC97B63), // terracotta
    )

/** Dark enough to read on every accent above; the theme's onSurface is not. */
private val AccentInk = Color(0xFF0E1728)

/**
 * Above this, an accent is light enough that dark ink reads better than white.
 * Tuned to the palette above rather than to a general rule: amber sits just over
 * it, the other three just under.
 */
private const val INK_SWITCH_LUMINANCE = 0.4f

/**
 * A list's colour, keyed on its id rather than its position: the dot on the
 * Lists screen and the header on that list's own screen have to agree, and the
 * task screen never learns the row index. Keying on the id also means deleting
 * one list does not recolour the ones under it.
 */
fun listAccent(listId: String): Color =
    ListAccents[Math.floorMod(listId.hashCode(), ListAccents.size)]

/** Ink for text and icons drawn on [listAccent]. */
fun onListAccent(accent: Color): Color =
    if (accent.luminance() >
        INK_SWITCH_LUMINANCE
    ) {
        AccentInk
    } else {
        Color.White
    }
