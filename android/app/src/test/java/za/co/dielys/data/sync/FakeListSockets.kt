package za.co.dielys.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import za.co.dielys.data.remote.AccessTokens
import za.co.dielys.data.remote.ListSocket
import za.co.dielys.data.remote.ListSockets
import za.co.dielys.data.remote.SOCKET_PING
import za.co.dielys.data.remote.SOCKET_PONG
import za.co.dielys.data.remote.SocketEvent

/**
 * A socket with a hand on both ends.
 *
 * The session logic is the whole reason [ListSockets] is an interface: a dropped
 * connection, a refused upgrade and a server that stops answering are one line
 * each here, and an emulator plus a firewall rule otherwise (H1).
 */
class FakeListSockets : ListSockets {
    /** Set to a status to refuse the upgrade instead of opening it. */
    var refuseWith: Int? = null

    val opened: MutableList<FakeListSocket> = mutableListOf()

    /** Every open, in order, for a test that has to wait for one. */
    val opens: Channel<FakeListSocket> = Channel(Channel.UNLIMITED)

    override fun open(
        listId: String,
        deviceId: String,
        token: String,
    ): ListSocket {
        val socket = FakeListSocket(listId, deviceId, token)
        opened += socket
        opens.trySend(socket)

        val refusal = refuseWith
        if (refusal == null) {
            socket.deliver(SocketEvent.Open)
        } else {
            socket.deliver(SocketEvent.Down(cause = RuntimeException("refused"), status = refusal))
            socket.endEvents()
        }
        return socket
    }

    /** The most recent socket for a list, or null if none was ever opened. */
    fun last(listId: String): FakeListSocket? = opened.lastOrNull { it.listId == listId }
}

class FakeListSocket(
    val listId: String,
    val deviceId: String,
    val token: String,
) : ListSocket {
    /**
     * On by default, because that is what the server does — the Durable Object
     * answers `ping` from the edge. Turned off to test what happens when it
     * stops (H3.11).
     */
    var answerPings: Boolean = true

    /** Everything the client said, pings included. */
    val sent: MutableList<String> = mutableListOf()

    val closed: CompletableDeferred<Unit> = CompletableDeferred()

    private val channel = Channel<SocketEvent>(Channel.UNLIMITED)

    override val events: ReceiveChannel<SocketEvent> get() = channel

    override fun send(text: String) {
        sent += text
        if (text == SOCKET_PING && answerPings) deliver(SocketEvent.Text(SOCKET_PONG))
    }

    override fun close() {
        closed.complete(Unit)
        channel.close()
    }

    /** Everything the client said that was not a heartbeat. */
    fun messages(): List<String> = sent.filter { it != SOCKET_PING }

    fun say(text: String) = deliver(SocketEvent.Text(text))

    /** The network went away mid-session: no status, because it did open. */
    fun drop() {
        deliver(SocketEvent.Down(cause = RuntimeException("connection reset"), status = null))
        endEvents()
    }

    internal fun deliver(event: SocketEvent) {
        channel.trySend(event)
    }

    internal fun endEvents() {
        channel.close()
    }
}

/**
 * The access token, without a session behind it. [refreshesTo] is what the next
 * refresh produces; null is a refresh token the server has revoked.
 */
class FakeTokens(
    private var token: String? = "access-token",
    private val refreshesTo: String? = "refreshed-access-token",
) : AccessTokens {
    var refreshes: Int = 0
        private set

    override suspend fun current(): String? = token

    override suspend fun refreshed(): String? {
        refreshes += 1
        token = refreshesTo
        return refreshesTo
    }
}
