package za.co.dielys.domain

/**
 * Fractional indexing (F5.5). Pure Kotlin — no `android.*`, no clock, no I/O (E1).
 *
 * This is a port of `server/src/domain/position.ts` and must stay byte-identical to
 * it. `protocol/fixtures/positions.json` is the shared contract that makes a
 * mismatch fail a build rather than fail in a shop (F1, F4).
 *
 * A key has an **integer part** whose first character encodes its own length, plus
 * an optional **fractional part**. Appending increments the integer, so the first
 * 62 items are two characters (`a0`..`az`) and the next 3,844 are three. Inserting
 * *between* two items is the only operation that lengthens a key.
 *
 * `BASE62` is in ASCII order, so keys sort correctly under plain byte comparison —
 * `compareTo` in Kotlin. Never sort these with a locale-aware comparator: it would
 * put "a" before "B" and silently reorder the list.
 *
 * Two devices inserting at the same spot while offline can generate the *same* key.
 * Nothing shared coordinates them. The list order is therefore `(position, id)`,
 * ascending, with the UUIDv7 breaking the tie — see [taskOrder] and H3.9.
 */
object Position {
    /** ASCII-ordered, so byte comparison and digit order agree. */
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /** Mirrors `MAX_POSITION_LENGTH` in `protocol/src/types.ts`. */
    const val MAX_LENGTH = 256

    /**
     * How many digits follow the widest integer prefix. "A" is the lowest prefix
     * character, and the encoding gives it the longest body.
     */
    private const val WIDEST_INTEGER_BODY = 26

    /** The smallest integer part the encoding allows. */
    private val SMALLEST_INTEGER = "A" + "0".repeat(WIDEST_INTEGER_BODY)

    /** The first key in an empty list. */
    const val FIRST = "a0"

    /**
     * Returns a position strictly between [before] and [after].
     *
     * `null` means "no neighbour on that side": `between(null, null)` is the first
     * item in an empty list, `between(last, null)` appends, `between(null, first)`
     * prepends.
     */
    fun between(
        before: String?,
        after: String?,
    ): String {
        if (before != null) validate(before)
        if (after != null) validate(after)
        if (before != null && after != null && before >= after) {
            throw PositionError("positions out of order: $before >= $after")
        }

        if (after == null) return if (before == null) FIRST else append(before)
        if (before == null) return prepend(after)
        return betweenBoth(before, after)
    }

    /**
     * Generates [count] keys in order between [before] and [after].
     *
     * Not a loop over [between] at the call site, because inserting n items one at
     * a time between the same pair makes each key a character longer than the last
     * — the classic way fractional indexing degrades. This spreads them instead.
     */
    fun betweenMany(
        before: String?,
        after: String?,
        count: Int,
    ): List<String> {
        if (count < 0) throw PositionError("count must be a non-negative integer, got $count")
        if (count == 0) return emptyList()
        if (count == 1) return listOf(between(before, after))

        if (after == null) {
            // Appending is cheap: walk forward from each new key.
            var cursor = before
            return List(count) {
                val next = between(cursor, null)
                cursor = next
                next
            }
        }

        if (before == null) {
            // Prepending, built backwards then reversed so the result is ascending.
            var cursor = after
            return List(count) {
                val next = between(null, cursor)
                cursor = next
                next
            }.reversed()
        }

        // Bisect: the middle key first, then fill both halves. Splitting this way
        // makes the keys grow with log(count) rather than with count.
        val half = count / 2
        val middle = between(before, after)
        return betweenMany(before, middle, half) +
            middle +
            betweenMany(middle, after, count - half - 1)
    }

    /** Throws unless [key] is a well-formed position. */
    fun validate(key: String) {
        if (key.isEmpty()) throw PositionError("position is empty")

        val integer = integerPart(key)
        val fraction = key.substring(integer.length)
        // A trailing zero is a second spelling of the same position; forbidding it
        // keeps the encoding canonical so equal keys are equal strings.
        if (fraction.endsWith("0")) throw PositionError("position has a trailing zero: $key")
        for (char in fraction) {
            if (BASE62.indexOf(char) == -1) throw PositionError("invalid digit in position: $key")
        }
    }

    fun isValid(key: String): Boolean =
        try {
            validate(key)
            true
        } catch (_: PositionError) {
            false
        }

    /**
     * The list order the whole system agrees on: ascending by position under plain
     * byte comparison, then by id. Offline devices can produce the same position;
     * the UUIDv7 breaks the tie identically on both, so both rows survive (H3.9).
     */
    fun <T> taskOrder(
        position: (T) -> String,
        id: (T) -> String,
    ): Comparator<T> = compareBy({ position(it) }, { id(it) })

    private fun prepend(after: String): String {
        val integer = integerPart(after)
        val fraction = after.substring(integer.length)

        // A key with a fractional part is already above its own integer part, so
        // the integer alone sits between it and everything below.
        if (integer == SMALLEST_INTEGER) return checkLength(integer + midpoint("", fraction))
        if (integer < after) return integer

        return decrementInteger(integer) ?: throw PositionError("cannot prepend any further")
    }

    private fun append(before: String): String {
        // The common case, and the one the integer part exists for.
        val integer = integerPart(before)
        val fraction = before.substring(integer.length)
        return incrementInteger(integer) ?: checkLength(integer + midpoint(fraction, null))
    }

    private fun betweenBoth(
        before: String,
        after: String,
    ): String {
        val beforeInteger = integerPart(before)
        val afterInteger = integerPart(after)
        if (beforeInteger == afterInteger) {
            return checkLength(
                beforeInteger +
                    midpoint(
                        before.substring(beforeInteger.length),
                        after.substring(afterInteger.length),
                    ),
            )
        }

        val incremented =
            incrementInteger(beforeInteger) ?: throw PositionError("integer part overflowed")
        // If the next whole integer still lands below `after`, use it: it is the
        // shortest key that fits.
        if (incremented < after) return incremented
        return checkLength(beforeInteger + midpoint(before.substring(beforeInteger.length), null))
    }

    /**
     * Repeatedly inserting at the same spot lengthens the key each time. The bound
     * is generous — thousands of nested inserts between one pair to reach it — but
     * a key the server would reject at the boundary (F3) must fail here, where the
     * message can say why, rather than as a rejected mutation later. [betweenMany]
     * is the fix when many keys are needed at once.
     */
    private fun checkLength(key: String): String {
        if (key.length > MAX_LENGTH) {
            throw PositionError(
                "position would be ${key.length} characters, over the $MAX_LENGTH bound",
            )
        }
        return key
    }

    /** The declared length of the integer part, read from its first character. */
    private fun integerLength(head: Char): Int {
        if (head in 'a'..'z') return head - 'a' + 2
        if (head in 'A'..'Z') return 'Z' - head + 2
        throw PositionError("invalid position head: $head")
    }

    private fun integerPart(key: String): String {
        if (key.isEmpty()) throw PositionError("position is empty")
        val length = integerLength(key[0])
        if (length > key.length) throw PositionError("position is too short: $key")
        val integer = key.substring(0, length)
        for (char in integer.substring(1)) {
            if (BASE62.indexOf(char) == -1) throw PositionError("invalid digit in position: $key")
        }
        return integer
    }

    /** Returns null when the integer part cannot go any higher. */
    private fun incrementInteger(integer: String): String? {
        val head = integer[0]
        val digits = integer.substring(1).toCharArray()

        var carry = true
        var i = digits.size - 1
        while (carry && i >= 0) {
            val next = BASE62.indexOf(digits[i]) + 1
            if (next == BASE62.length) {
                digits[i] = BASE62[0]
            } else {
                digits[i] = BASE62[next]
                carry = false
            }
            i -= 1
        }

        if (!carry) return head + String(digits)

        // Every digit rolled over, so the integer part needs to change length.
        if (head == 'Z') return "a" + BASE62[0]
        if (head == 'z') return null

        val nextHead = head + 1
        // Crossing from the negative half shortens; staying in the positive half
        // lengthens. The length is encoded in the head, so both stay consistent.
        val resized = if (nextHead > 'a') String(digits) + BASE62[0] else String(digits).dropLast(1)
        return nextHead + resized
    }

    /** Returns null when the integer part cannot go any lower. */
    private fun decrementInteger(integer: String): String? {
        val head = integer[0]
        val digits = integer.substring(1).toCharArray()
        val last = BASE62[BASE62.length - 1]

        var borrow = true
        var i = digits.size - 1
        while (borrow && i >= 0) {
            val next = BASE62.indexOf(digits[i]) - 1
            if (next == -1) {
                digits[i] = last
            } else {
                digits[i] = BASE62[next]
                borrow = false
            }
            i -= 1
        }

        if (!borrow) return head + String(digits)

        if (head == 'a') return "Z" + last
        if (head == 'A') return null

        val nextHead = head - 1
        val resized = if (nextHead < 'Z') String(digits) + last else String(digits).dropLast(1)
        return nextHead + resized
    }

    /**
     * The shortest fractional part strictly between [a] and [b], where both are
     * fractional parts of the same integer part. [b] of null means "no upper bound
     * within this integer".
     */
    private fun midpoint(
        a: String,
        b: String?,
    ): String {
        if (b != null && a >= b) throw PositionError("fractions out of order: $a >= $b")
        if (a.endsWith("0") || b?.endsWith("0") == true) {
            throw PositionError("fraction has a trailing zero")
        }

        if (b != null) {
            // Strip the shared prefix and recurse — the answer keeps that prefix,
            // and what is left is the same problem on shorter strings. Past the end
            // of `a` the implied digit is "0", which is what makes "ab" and "ab0V"
            // compare the way the encoding says they should.
            var shared = 0
            while (shared < b.length && (a.getOrNull(shared) ?: '0') == b[shared]) shared += 1
            if (shared > 0) {
                return b.substring(0, shared) + midpoint(a.drop(shared), b.substring(shared))
            }
        }

        val digitA = if (a.isEmpty()) 0 else BASE62.indexOf(a[0])
        val digitB = if (b == null) BASE62.length else BASE62.indexOf(b[0])

        if (digitB - digitA > 1) {
            // Room for a digit strictly between them: one character is enough.
            // Integer arithmetic, ties upward — the same value the reference
            // implementation gets from Math.round(0.5 * (digitA + digitB)).
            return BASE62[(digitA + digitB + 1) / 2].toString()
        }

        // The digits are consecutive, so the answer has to be longer.
        if (b != null && b.length > 1) return b.substring(0, 1)
        return BASE62[digitA] + midpoint(a.drop(1), null)
    }
}

class PositionError(
    message: String,
) : Exception(message)
