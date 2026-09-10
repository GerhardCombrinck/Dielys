package za.co.dielys.domain

/**
 * Seeding a list order that nobody has set yet. Pure Kotlin — no `android.*`, no
 * clock, no I/O (E1).
 *
 * Lists made before per-account ordering existed have no key at all, and neither
 * does one that arrived by invite. Rather than invent an order for the whole
 * screen at startup, the first drag gives every keyless row a key **in the order
 * it is already shown** — so what the person sees before the drag is what they
 * see after it, minus the row they moved.
 */
fun <T> seedPositions(
    ordered: List<T>,
    positionOf: (T) -> String?,
    withPosition: (T, String) -> T,
): List<T> {
    if (ordered.all { positionOf(it) != null }) return ordered
    var previous: String? = null
    return ordered.map { row ->
        val position = positionOf(row) ?: Position.between(previous, null)
        previous = position
        if (positionOf(row) == position) row else withPosition(row, position)
    }
}
