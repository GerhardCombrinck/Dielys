package za.co.dielys.data.remote

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A normal, deliberate close. */
private const val CLOSE_NORMAL = 1000

/**
 * OkHttp implementation of [ListSockets].
 *
 * All it does is turn callbacks on OkHttp's thread into events on a channel. It
 * makes no decisions — not about the handshake, not about reconnecting, not about
 * what a status means — because every one of those is a rule worth testing, and a
 * rule that lives in here can only be tested against a real socket.
 */
@Singleton
class OkHttpListSockets
    @Inject
    constructor(
        client: OkHttpClient,
        private val baseUrl: HttpUrl,
    ) : ListSockets {
        /**
         * The shared client with its read timeout removed. A socket that is doing
         * its job is silent between changes — on this app, usually for hours — and
         * a read timeout would tear down a healthy connection on a schedule. The
         * heartbeat is what decides a socket is dead (H3.11), not a timer that
         * cannot tell quiet from broken.
         *
         * `newBuilder` shares the connection pool and dispatcher, so this is a
         * different configuration of the same client, not a second one.
         */
        private val socketClient: OkHttpClient =
            client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

        override fun open(
            listId: String,
            deviceId: String,
            token: String,
        ): ListSocket {
            val url =
                baseUrl
                    .newBuilder()
                    .addPathSegment("lists")
                    .addPathSegment(listId)
                    .addPathSegment("ws")
                    .addQueryParameter("deviceId", deviceId)
                    .build()

            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .build()

            // Unlimited, because the alternative is dropping a change on the floor
            // when the session loop is busy applying the previous one. A list's
            // changelog is small and the loop drains continuously; this is a buffer,
            // not a queue anything accumulates in.
            val events = Channel<SocketEvent>(Channel.UNLIMITED)
            val socket = socketClient.newWebSocket(request, Relay(events))
            return OkHttpListSocket(socket, events)
        }
    }

private class Relay(
    private val events: Channel<SocketEvent>,
) : WebSocketListener() {
    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        events.trySend(SocketEvent.Open)
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        events.trySend(SocketEvent.Text(text))
    }

    /** The peer wants to close. Answer, and let `onClosed` end the channel. */
    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        webSocket.close(CLOSE_NORMAL, null)
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        events.trySend(SocketEvent.Down(cause = null, status = null))
        events.close()
    }

    /**
     * Both "the network went away mid-session" and "the upgrade was refused". The
     * status separates them: there is only a response when the socket never opened.
     */
    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        events.trySend(SocketEvent.Down(cause = t, status = response?.code))
        events.close()
    }
}

private class OkHttpListSocket(
    private val socket: WebSocket,
    private val channel: Channel<SocketEvent>,
) : ListSocket {
    override val events: ReceiveChannel<SocketEvent> get() = channel

    override fun send(text: String) {
        // Returns false when the socket is already closing or the send queue is
        // full. Neither is worth reacting to here: the session finds out from the
        // Down event that follows, which is the one place that decision lives.
        socket.send(text)
    }

    override fun close() {
        socket.close(CLOSE_NORMAL, null)
        // Closing for send only — anything already delivered is still readable, so
        // a frame that arrived while this was being called is not lost. Without it
        // a peer that never answers the close would leave the session loop waiting
        // on a socket nobody is coming back to.
        channel.close()
    }
}
