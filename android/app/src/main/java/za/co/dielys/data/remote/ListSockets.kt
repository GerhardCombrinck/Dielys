package za.co.dielys.data.remote

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * The WebSocket, as the sync engine sees it — the same seam [SyncApi] is, and for
 * the same reason: the session logic (handshake, gap detection, heartbeat,
 * reconnect) is where the bugs live, and none of it should need an emulator or a
 * real server to test (H1).
 *
 * Text frames only. Every message on this socket is one of the JSON types in
 * `protocol/`, plus the literal `ping`/`pong` pair the Durable Object answers
 * from the edge without waking up.
 */
interface ListSockets {
    /**
     * Opens `GET /lists/{listId}/ws`.
     *
     * [token] is presented as `Authorization: Bearer` on the upgrade request, not
     * in the `hello` message: L3 requires the *Worker* to authorise before
     * anything reaches a `ListRoom`, and the Worker never sees a frame sent after
     * the upgrade has completed.
     *
     * [deviceId] rides on the query string because the DO reads it there to fill
     * in the socket's attachment before `hello` arrives. Without it there is a
     * window — short, but real — in which a connected device is invisible to the
     * wake fan-out and gets an FCM push for a change its socket is about to
     * deliver (M2).
     *
     * Never throws. A refused upgrade arrives as [SocketEvent.Down] carrying the
     * status, because the caller has to tell 401 (refresh and try again) from 403
     * (this account is not a member, and no retry changes that) — and a thrown
     * exception at open time would need the same handling in a second place.
     */
    fun open(
        listId: String,
        deviceId: String,
        token: String,
    ): ListSocket
}

/** One open socket. Closing it is what ends [events]. */
interface ListSocket {
    /**
     * Everything the socket says, in order, until it closes. Buffered: the session
     * loop must never be the reason a frame is dropped, and OkHttp delivers on its
     * own thread.
     */
    val events: ReceiveChannel<SocketEvent>

    fun send(text: String)

    /** Idempotent. Ends [events] whether or not the peer answers. */
    fun close()
}

sealed interface SocketEvent {
    /** The upgrade completed. Nothing has been said yet. */
    data object Open : SocketEvent

    data class Text(
        val text: String,
    ) : SocketEvent

    /**
     * The socket is finished, for any reason: closed by either side, dropped by
     * the network, or refused before it opened.
     *
     * [status] is the HTTP status of a refused upgrade and null for everything
     * else — there is no status on a socket that opened and then died.
     */
    data class Down(
        val cause: Throwable?,
        val status: Int?,
    ) : SocketEvent
}

/** The heartbeat, which the Hibernation API answers without waking the DO. */
const val SOCKET_PING = "ping"
const val SOCKET_PONG = "pong"
