package za.co.dielys.ui.theme

import androidx.compose.ui.graphics.Color
import za.co.dielys.domain.ACCENT_COUNT

/**
 * The colours a list can wear — [ACCENT_COUNT] of them, checked below, because
 * the data layer picks an index without being able to see this list.
 *
 * Eight, not four: four guarantees a repeat on a fifth list, and two lists
 * clashed one time in four even before that (#57). Eight is enough that a
 * household never sees a duplicate in practice, and still fits one row of
 * swatches in the picker.
 *
 * All mid-tone on purpose — each one has to read on the Sand background of the
 * light theme *and* on the Navy of the dark one, because it is the same stored
 * index either way. The first four are the original palette, in its original
 * order, so no existing list changes colour when it is given a stored one.
 *
 * Consecutive entries are deliberately far apart on the wheel: colours are
 * handed out in this order, so the first few lists a person makes look as
 * different from each other as the palette allows.
 */
private val Accents =
    listOf(
        Color(0xFFE8A33D), // amber
        Color(0xFF7FA893), // sage
        Color(0xFF7C93C4), // dusty blue
        Color(0xFFC97B63), // terracotta
        Color(0xFFC77B94), // rose
        Color(0xFF5FA6A4), // teal
        Color(0xFF9B8AC9), // lilac
        Color(0xFFA9A85C), // olive
    ).also { check(it.size == ACCENT_COUNT) { "the palette must have ACCENT_COUNT colours" } }

/**
 * The colour for a stored palette index. Wrapped rather than bounds-checked: a
 * database written by a build with a longer palette must still open on one with
 * a shorter, showing *a* colour rather than crashing.
 */
fun accentColor(index: Int): Color = Accents[Math.floorMod(index, Accents.size)]

/**
 * The colour of a list that has no stored one yet — a list joined by invite in
 * the seconds before `ListAccents` assigns it, or any list at all on the very
 * first launch after the upgrade that added the table.
 *
 * Hashed on the id, which is what every list used before #57: it cannot avoid
 * duplicates, but it is stable, so nothing flickers while the real colour is
 * being worked out, and deleting one list never recolours the ones under it.
 */
fun listAccent(listId: String): Color = accentColor(listId.hashCode())
