package za.co.dielys.domain

/**
 * Wall-clock time, as an interface so tests can pin it.
 *
 * Nothing that orders anything reads this. Device clocks are never trusted for
 * ordering (F5.9) — that is what server-assigned `seq` and `serverTimestamp` are
 * for. This exists for values a patch has to carry, such as the `deletedAt` of a
 * tombstone, and for the time bits of a UUIDv7.
 */
fun interface Clock {
    fun nowMillis(): Long
}

/** The device clock. The only implementation outside tests. */
object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
