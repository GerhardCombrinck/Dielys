package za.co.dielys.domain

import java.util.UUID
import kotlin.random.Random

/**
 * UUIDv7 (RFC 9562). Entity ids are client-generated and the server never mints
 * one (F5.1), so the client needs an id that is unique without coordination *and*
 * roughly time-ordered — the ordering is what makes `(position, id)` a stable
 * tiebreak when two offline devices pick the same fractional index (H3.9).
 *
 * Pure Kotlin (E1): no `android.*`, no `androidx.*`. The clock is a parameter, so
 * tests get deterministic ids without a fake framework.
 *
 * Layout: 48 bits of Unix milliseconds, 4 bits of version, 12 random, 2 bits of
 * variant, 62 random.
 */
object Uuid7 {
    private const val MILLIS_MASK = 0xFFFF_FFFF_FFFFL
    private const val MILLIS_SHIFT = 16
    private const val VERSION = 0x7L
    private const val VERSION_SHIFT = 12
    private const val RAND_A_BOUND = 0x1000
    private const val VARIANT_CLEAR = 0x3FFF_FFFF_FFFF_FFFFL

    fun generate(
        unixMillis: Long,
        random: Random = Random.Default,
    ): String {
        val randA = random.nextInt(RAND_A_BOUND).toLong()
        val high =
            ((unixMillis and MILLIS_MASK) shl MILLIS_SHIFT) or
                (VERSION shl VERSION_SHIFT) or
                randA
        // Clearing the top two bits then setting bit 63 gives the RFC 4122
        // variant, 0b10. Long.MIN_VALUE is bit 63 alone.
        val low = (random.nextLong() and VARIANT_CLEAR) or Long.MIN_VALUE
        return UUID(high, low).toString()
    }

    /**
     * Uses the device clock, which is fine here and only here: an id needs to be
     * unique and roughly increasing, never authoritative. Nothing in sync ordering
     * reads it as a time (F5.9).
     */
    fun generate(): String = generate(System.currentTimeMillis())
}
