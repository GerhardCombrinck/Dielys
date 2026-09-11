package za.co.dielys.domain

/**
 * How many colours a list can be (#57).
 *
 * Here rather than beside the palette so the data layer can pick a colour
 * without reaching into the UI for one. `ui/theme/ListAccent.kt` holds exactly
 * this many and checks that it does.
 */
const val ACCENT_COUNT = 8

/**
 * Which colour the next list should get: whichever the fewest lists already
 * wear (#57).
 *
 * Least-used rather than "the next unused one" because there is no next one
 * once every colour is taken — a household with nine lists has to repeat, and
 * repeating the colour that is on screen once is better than repeating the one
 * that is on screen three times.
 *
 * Ties go to the lowest index, which is what makes the first few lists on a new
 * phone come out amber, sage, dusty blue, … in that order rather than at
 * random. Deliberate: predictable is easier to live with than novel, and the
 * picker is there for anyone who disagrees.
 *
 * @param usage how many live lists wear each index; absent means none do.
 */
fun leastUsedAccent(
    usage: Map<Int, Int>,
    paletteSize: Int,
): Int {
    require(paletteSize > 0) { "the palette cannot be empty" }
    return (0 until paletteSize).minBy { usage[it] ?: 0 }
}
