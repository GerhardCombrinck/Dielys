package za.co.dielys.data.push

/** The push type this app understands. Anything else is ignored (M1). */
const val PUSH_TYPE_SYNC = "sync"

/**
 * A wake push, parsed.
 *
 * This is a hint that something changed, never the change itself: the payload
 * carries no title, no note and no list name, because a push travels through
 * Google's servers and the standard says list content does not (M1). The client
 * reacts by syncing and then reads Room, which is the only place content lives.
 *
 * Nothing downstream actually needs these two fields — [PushHandler] syncs every
 * list regardless — but parsing them proves the payload is one of ours before
 * spending a network round trip on it.
 */
data class WakeHint(
    val listId: String,
    val seq: Long,
) {
    companion object {
        /**
         * `null` for anything that is not a wake push this build recognises.
         *
         * FCM hands over `Map<String, String>`, so `seq` arrives as decimal text
         * rather than a number — a constraint of the transport, not a second
         * encoding of the protocol (see `protocol/src/push.ts`).
         */
        fun from(data: Map<String, String>): WakeHint? {
            if (data["type"] != PUSH_TYPE_SYNC) return null
            val listId = data["listId"]?.takeIf { it.isNotEmpty() } ?: return null
            val seq = data["seq"]?.toLongOrNull() ?: return null
            return WakeHint(listId = listId, seq = seq)
        }
    }
}
