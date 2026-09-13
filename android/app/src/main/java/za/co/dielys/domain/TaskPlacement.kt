package za.co.dielys.domain

/**
 * Where the top of a list actually is, for a new item arriving there (#62).
 * Pure Kotlin — no `android.*`, no clock, no I/O (E1).
 *
 * A star is not a sort key: starring *writes a position* that carries the row to
 * the top, and a starred row later dragged back down stays down (see
 * `DielysRepository.setStarred`). So "under the starred ones" can only mean the
 * run of them at the top — the block a star builds. Counting every starred row
 * on the list instead would send a new item past whatever unstarred rows
 * somebody had deliberately put above a dragged-down star.
 *
 * Returns the index the new row belongs at, which is 0 when nothing up there is
 * starred — the plain "newest first" this grew out of.
 *
 * The screen needs the same answer as the write does: the placeholder row shown
 * while typing has to appear where the real one will land, so both ask here.
 */
fun <T> spotUnderStarred(
    active: List<T>,
    starred: (T) -> Boolean,
): Int = active.takeWhile(starred).size
