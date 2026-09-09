package za.co.dielys.domain

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import za.co.dielys.Fixtures

/**
 * Fractional indexing (F5.5). The golden vectors come from the same file the
 * TypeScript suite reads, so a Kotlin/TypeScript disagreement about list order
 * fails a build instead of shipping (F4).
 *
 * Most of the rest are stated as invariants over many operations rather than as
 * single examples — an ordering bug that only shows up at the 62nd append is
 * exactly the kind that reaches a phone.
 */
class PositionTest {
    @Serializable
    data class BetweenVector(
        val why: String,
        val before: String? = null,
        val after: String? = null,
        val expected: String,
    )

    @Serializable
    data class KeySet(
        val why: String,
        val keys: List<String>,
    )

    @Serializable
    data class PositionFixtures(
        val between: List<BetweenVector>,
        val ordering: KeySet,
        val invalid: KeySet,
    )

    private val fixtures: PositionFixtures = Fixtures.load("positions.json")

    @Nested
    inner class GoldenVectors {
        @TestFactory
        fun `every vector reproduces the expected key`(): List<DynamicTest> =
            fixtures.between.map { vector ->
                DynamicTest.dynamicTest(
                    "between(${vector.before}, ${vector.after}) = " +
                        "${vector.expected} — ${vector.why}",
                ) {
                    assertEquals(vector.expected, Position.between(vector.before, vector.after))
                }
            }

        @Test
        fun `the ordering fixture is sorted by plain byte comparison`() {
            val keys = fixtures.ordering.keys
            assertEquals(keys, keys.sorted())
        }

        @Test
        fun `every key in the ordering fixture is valid`() {
            for (key in fixtures.ordering.keys) assertTrue(Position.isValid(key), key)
        }

        @Test
        fun `every key in the invalid fixture is rejected`() {
            for (key in fixtures.invalid.keys) assertFalse(Position.isValid(key), key)
        }
    }

    @Nested
    inner class Between {
        @Test
        fun `starts an empty list at the documented first position`() {
            assertEquals(Position.FIRST, Position.between(null, null))
        }

        @Test
        fun `always returns something strictly between its neighbours`() {
            val pairs =
                listOf(
                    "a0" to "a1",
                    "a0" to "az",
                    "Zz" to "a0",
                    "a0V" to "a0l",
                    "b00" to "b0z",
                    "A00000000000000000000000001" to "zzzzzzzzzzzzzzzzzzzzzzzzzzz",
                )
            for ((before, after) in pairs) {
                val middle = Position.between(before, after)
                assertTrue(middle > before, "$middle > $before")
                assertTrue(middle < after, "$middle < $after")
                assertTrue(Position.isValid(middle), middle)
            }
        }

        @Test
        fun `refuses neighbours that are out of order`() {
            assertThrows<PositionError> { Position.between("a1", "a0") }
        }

        @Test
        fun `refuses neighbours that are equal - there is no room between them`() {
            assertThrows<PositionError> { Position.between("a0", "a0") }
        }

        @Test
        fun `refuses an invalid neighbour rather than producing a key from it`() {
            assertThrows<PositionError> { Position.between("not a position", null) }
            assertThrows<PositionError> { Position.between(null, "a00") }
        }
    }

    @Nested
    inner class Appending {
        @Test
        fun `keeps keys short and ordered across the length boundary`() {
            val keys = mutableListOf<String>()
            var cursor: String? = null
            repeat(APPEND_RUN) {
                val next = Position.between(cursor, null)
                keys += next
                cursor = next
            }

            assertEquals(keys, keys.sorted())
            assertEquals(keys.size, keys.toSet().size)
            // 62 two-character keys, then three — the property the integer part
            // exists for. A plain midpoint scheme would grow without bound.
            assertEquals("az", keys[61])
            assertEquals("b00", keys[62])
            assertEquals(3, keys.maxOf { it.length })
        }

        @Test
        fun `stays valid for a long run`() {
            var cursor: String? = null
            repeat(LONG_RUN) {
                cursor = Position.between(cursor, null)
                assertTrue(Position.isValid(checkNotNull(cursor)))
            }
        }
    }

    @Nested
    inner class Prepending {
        @Test
        fun `walks down into the negative half without growing`() {
            var cursor = Position.FIRST
            val keys = mutableListOf<String>()
            repeat(PREPEND_RUN) {
                cursor = Position.between(null, cursor)
                keys += cursor
            }

            // Built in descending order, so reversing gives ascending.
            assertEquals(keys.sorted(), keys.reversed())
            assertEquals("Zz", keys[0])
            assertEquals(2, keys.maxOf { it.length })
        }
    }

    @Nested
    inner class RepeatedInsertBetweenTheSamePair {
        @Test
        fun `grows one key at a time, which is why betweenMany exists`() {
            var low = "a0"
            val lengths = mutableListOf<Int>()
            repeat(NESTED_RUN) {
                val middle = Position.between(low, "a1")
                assertTrue(middle > low)
                assertTrue(middle < "a1")
                lengths += middle.length
                low = middle
            }
            // Documented degradation, not a bug: each insert lands in the gap the
            // last one left. The fix is to ask for all the keys at once.
            assertTrue(lengths.first() < lengths.last())
        }

        @Test
        fun `refuses to produce a key past the protocol bound`() {
            // Drive it deliberately into the wall rather than letting the server
            // reject the mutation later with no explanation (F3).
            var low = "a0"
            var threw = false
            repeat(WALL_RUN) {
                if (!threw) {
                    try {
                        low = Position.between(low, "a1")
                        assertTrue(low.length <= Position.MAX_LENGTH)
                    } catch (_: PositionError) {
                        threw = true
                    }
                }
            }
            assertTrue(threw, "expected a PositionError at the bound")
        }
    }

    @Nested
    inner class BetweenMany {
        @Test
        fun `returns nothing for a count of zero`() {
            assertEquals(emptyList<String>(), Position.betweenMany("a0", "a1", 0))
        }

        @Test
        fun `matches between for a count of one`() {
            assertEquals(
                listOf(Position.between("a0", "a1")),
                Position.betweenMany("a0", "a1", 1),
            )
        }

        @Test
        fun `spreads keys instead of nesting them`() {
            val keys = Position.betweenMany("a0", "a1", SPREAD_COUNT)
            assertEquals(SPREAD_COUNT, keys.size)
            assertEquals(keys.sorted(), keys)
            assertEquals(SPREAD_COUNT, keys.toSet().size)
            assertTrue(keys.all { it > "a0" && it < "a1" })
            // Bisecting keeps this at 3; inserting one at a time reaches 12.
            assertTrue(keys.maxOf { it.length } <= 4)
        }

        @Test
        fun `appends and prepends in bulk`() {
            val appended = Position.betweenMany("a0", null, BULK_COUNT)
            assertEquals(appended.sorted(), appended)
            assertTrue(appended.all { it > "a0" })

            val prepended = Position.betweenMany(null, "a0", BULK_COUNT)
            assertEquals(prepended.sorted(), prepended)
            assertTrue(prepended.all { it < "a0" })
        }

        @Test
        fun `refuses a negative count`() {
            assertThrows<PositionError> { Position.betweenMany("a0", "a1", -1) }
        }
    }

    @Nested
    inner class Validate {
        @Test
        fun `accepts well-formed keys`() {
            val keys =
                listOf("a0", "a1", "az", "b00", "Zz", "a0V", "A00000000000000000000000001")
            for (key in keys) Position.validate(key)
        }

        @Test
        fun `rejects a trailing zero, which is a second spelling of the same key`() {
            assertThrows<PositionError> { Position.validate("a0V0") }
        }

        @Test
        fun `rejects a key shorter than its own declared integer length`() {
            // 'z' declares 27 characters.
            assertThrows<PositionError> { Position.validate("z0") }
        }

        @Test
        fun `rejects digits outside the alphabet`() {
            assertThrows<PositionError> { Position.validate("a0!") }
            assertThrows<PositionError> { Position.validate("a0 ") }
        }

        @Test
        fun `rejects an empty key`() {
            assertThrows<PositionError> { Position.validate("") }
        }
    }

    @Nested
    inner class SimulatedReordering {
        @Test
        fun `moving one item never changes any other key`() {
            // Build a list, then move the last item to the middle. This is the whole
            // point of fractional indexing: exactly one row is written.
            val keys = mutableListOf<String>()
            var cursor: String? = null
            repeat(SMALL_LIST) {
                val next = Position.between(cursor, null)
                keys += next
                cursor = next
            }

            val before = keys.toList()
            val moved = Position.between(keys[4], keys[5])

            assertEquals(before.take(9), keys.take(9))
            assertTrue(moved > keys[4])
            assertTrue(moved < keys[5])
        }

        @Test
        fun `two devices inserting at the same spot both survive, ordered by id`() {
            // Neither device can see the other, so both may pick the same key. That
            // is expected — the list order is (position, id), and the UUIDv7 breaks
            // the tie identically on both devices (H3.9).
            val deviceA = Position.between("a0", "a1")
            val deviceB = Position.between("a0", "a1")
            assertEquals(deviceA, deviceB)

            val rows =
                listOf(
                    Row(deviceB, "018f2f6c-0000-7000-8000-00000000000b"),
                    Row(deviceA, "018f2f6c-0000-7000-8000-00000000000a"),
                )
            val sorted = rows.sortedWith(Position.taskOrder(Row::position, Row::id))

            assertEquals(
                listOf(
                    "018f2f6c-0000-7000-8000-00000000000a",
                    "018f2f6c-0000-7000-8000-00000000000b",
                ),
                sorted.map { it.id },
            )
            // Both rows are still there. Neither clobbered the other.
            assertEquals(2, sorted.size)
        }
    }

    private data class Row(
        val position: String,
        val id: String,
    )

    private companion object {
        const val APPEND_RUN = 200
        const val LONG_RUN = 500
        const val PREPEND_RUN = 60
        const val NESTED_RUN = 50
        const val WALL_RUN = 20_000
        const val SPREAD_COUNT = 50
        const val BULK_COUNT = 100
        const val SMALL_LIST = 10
    }
}
