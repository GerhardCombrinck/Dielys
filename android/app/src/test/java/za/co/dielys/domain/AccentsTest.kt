package za.co.dielys.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #57: what stops two lists on one screen wearing the same dot.
 *
 * The duplicate it replaces was not a rare accident — the old hash of the id
 * over four colours clashed one time in four on the *second* list — so these
 * cover the ordinary cases rather than the edges.
 */
class AccentsTest {
    @Test
    fun `an empty screen starts at the first colour`() {
        assertEquals(0, leastUsedAccent(emptyMap(), paletteSize = 8))
    }

    @Test
    fun `the first few lists each get a colour nobody else has`() {
        val usage = mutableMapOf<Int, Int>()
        val picked =
            List(8) {
                val accent = leastUsedAccent(usage, paletteSize = 8)
                usage[accent] = (usage[accent] ?: 0) + 1
                accent
            }

        assertEquals((0 until 8).toList(), picked)
    }

    @Test
    fun `past the palette it repeats the colour that is on screen least`() {
        // Every colour taken once, and one of them twice: the ninth list has to
        // duplicate something, and it must not be the one already doubled up.
        val usage = (0 until 8).associateWith { 1 } + (3 to 2)

        assertEquals(0, leastUsedAccent(usage, paletteSize = 8))
    }

    @Test
    fun `a colour freed by a deleted list is handed out before any is repeated`() {
        // What `ListAccentDao.usage` produces once a list is tombstoned: its
        // colour simply stops being counted.
        val usage = mapOf(0 to 1, 1 to 1, 3 to 1)

        assertEquals(2, leastUsedAccent(usage, paletteSize = 8))
    }

    @Test
    fun `ties go to the lowest index, so a fresh phone is not random`() {
        assertEquals(2, leastUsedAccent(mapOf(0 to 1, 1 to 1), paletteSize = 8))
    }
}
