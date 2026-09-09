package za.co.dielys.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The one timestamp format that crosses the wire.
 *
 * The server round-trips every timestamp it is given through
 * `new Date(Date.parse(v)).toISOString()` and rejects anything that does not come
 * back identical, so the format is exact: always UTC, always three digits of
 * milliseconds, always a literal `Z`. `Instant.toString()` is not good enough — it
 * drops the milliseconds when they are zero, which fails one write in a thousand.
 *
 * This is only ever used for a *value* a patch carries, such as the `deletedAt` of
 * a tombstone. Nothing here is used for ordering: device clocks are never trusted
 * for that (F5.9).
 */
object Timestamps {
    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun iso(unixMillis: Long): String = FORMAT.format(Instant.ofEpochMilli(unixMillis))
}
