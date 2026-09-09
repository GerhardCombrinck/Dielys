package za.co.dielys.data.sync

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import za.co.dielys.data.local.DeviceIdentity
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.remote.AccessTokens
import za.co.dielys.data.remote.CatchUpResponse
import za.co.dielys.data.remote.ChangeMessage
import za.co.dielys.data.remote.ClientHello
import za.co.dielys.data.remote.DielysJson
import za.co.dielys.data.remote.ListSocket
import za.co.dielys.data.remote.ListSockets
import za.co.dielys.data.remote.MutationAck
import za.co.dielys.data.remote.SOCKET_PING
import za.co.dielys.data.remote.SOCKET_PONG
import za.co.dielys.data.remote.ServerError
import za.co.dielys.data.remote.ServerHelloError
import za.co.dielys.data.remote.ServerHelloOk
import za.co.dielys.data.remote.ServerMessage
import za.co.dielys.data.remote.SocketEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** How often the client says `ping`, and how long it waits for the `pong`. */
const val SOCKET_PING_MILLIS = 30_000L

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_SERVER_ERROR = 500

/** Why one socket session ended, in the terms the reconnect loop needs. */
sealed interface SessionEnd {
    /**
     * Try again. [handshaked] says whether this attempt got as far as `hello-ok`,
     * which is what separates "the connection is flapping" from "one long session
     * finally dropped" — only the second resets the backoff.
     */
    data class Transient(
        val reason: String,
        val handshaked: Boolean,
    ) : SessionEnd

    /**
     * Reconnecting this list changes nothing: the server refuses this build's
     * protocol version, or this account is not a member of it. Costs nothing to
     * stop — the socket is a latency optimisation, and the HTTP catch-up path is
     * untouched by this.
     */
    data class Permanent(
        val reason: String,
    ) : SessionEnd

    /** The refresh token is dead. Nothing reconnects until somebody signs in. */
    data object SessionGone : SessionEnd
}

/** What one server message means for the session that received it. */
private sealed interface Step {
    data object Continue : Step

    data object Handshaked : Step

    data class Stop(
        val end: SessionEnd,
    ) : Step
}

/**
 * One socket, from the upgrade to whatever ends it.
 *
 * The socket is a latency optimisation and nothing else (see `docs/SYNC.md`): a
 * change arriving here is applied through [ChangeApplier], the same function the
 * `?since=` catch-up uses, and correctness comes from the cursor and the gap check
 * (F5.6) rather than from the socket having delivered anything. Everything this
 * class can get wrong costs latency; none of it can cost a change.
 *
 * It never sends a mutation. The outbox drains from `WorkManager` over HTTP,
 * because that has to keep working with no socket open and the app not running
 * (H3.4) — a second write path here would be a second place for F5.2 to be wrong.
 */
@Singleton
class ListSocketSession
    @Inject
    constructor(
        private val sockets: ListSockets,
        private val tokens: AccessTokens,
        private val device: DeviceIdentity,
        private val db: DielysDatabase,
        private val applier: ChangeApplier,
        private val engine: SyncEngine,
    ) {
        /** Opens a socket and returns when it is finished. Never throws. */
        suspend fun run(listId: String): SessionEnd {
            val token = tokens.current() ?: tokens.refreshed() ?: return SessionEnd.SessionGone
            val socket = sockets.open(listId, device.deviceId, token)
            return try {
                coroutineScope {
                    val awaitingPong = AtomicBoolean(false)
                    val heartbeat = launch { beat(socket, awaitingPong) }
                    try {
                        consume(listId, socket, awaitingPong)
                    } finally {
                        heartbeat.cancel()
                    }
                }
            } finally {
                socket.close()
            }
        }

        /**
         * H3.11, the whole of it. A socket that has silently died looks exactly
         * like one with nothing to say, and the difference only shows when
         * something is expected back.
         *
         * The Durable Object answers `ping` from the edge without waking up, so
         * this costs a frame and nothing else. One interval of grace: a missed
         * pong closes the socket, the session ends, and the reconnect pulls
         * `?since=cursor` — never a resync from scratch.
         */
        private suspend fun beat(
            socket: ListSocket,
            awaitingPong: AtomicBoolean,
        ) {
            while (true) {
                delay(SOCKET_PING_MILLIS)
                if (awaitingPong.get()) {
                    socket.close()
                    return
                }
                awaitingPong.set(true)
                socket.send(SOCKET_PING)
            }
        }

        private suspend fun consume(
            listId: String,
            socket: ListSocket,
            awaitingPong: AtomicBoolean,
        ): SessionEnd {
            var handshaked = false
            for (event in socket.events) {
                when (event) {
                    is SocketEvent.Open -> socket.send(hello(listId))
                    is SocketEvent.Down -> return ended(event, handshaked)
                    is SocketEvent.Text ->
                        when (val step = onText(listId, event.text, awaitingPong)) {
                            is Step.Stop -> return step.end
                            is Step.Handshaked -> handshaked = true
                            is Step.Continue -> Unit
                        }
                }
            }
            // The channel closed without a Down, which is the close() above
            // racing the last event. Nothing is wrong; reconnect.
            return SessionEnd.Transient("closed", handshaked)
        }

        private suspend fun hello(listId: String): String {
            val cursor = db.syncState().cursor(listId) ?: 0L
            return DielysJson.wire.encodeToString(
                ClientHello.serializer(),
                ClientHello(listId = listId, cursor = cursor, deviceId = device.deviceId),
            )
        }

        private suspend fun onText(
            listId: String,
            text: String,
            awaitingPong: AtomicBoolean,
        ): Step {
            if (text == SOCKET_PONG) {
                awaitingPong.set(false)
                return Step.Continue
            }

            val message =
                try {
                    DielysJson.wire.decodeFromString(ServerMessage.serializer(), text)
                } catch (_: SerializationException) {
                    // A message this build cannot read — a type a newer server
                    // sends, or something malformed. Ignored, never applied and
                    // never fatal (F2, F3): an old client meeting a new server
                    // must degrade to a slower sync, not a dropped socket.
                    return Step.Continue
                }

            return handle(listId, message)
        }

        private suspend fun handle(
            listId: String,
            message: ServerMessage,
        ): Step =
            when (message) {
                is ServerHelloOk -> {
                    // maxSeq is how the client learns at once that it is behind,
                    // without waiting for the next change to arrive.
                    applier.noteServerMaxSeq(listId, message.maxSeq)
                    if (message.maxSeq > (db.syncState().cursor(listId) ?: 0L)) {
                        engine.catchUp(listId)
                    }
                    Step.Handshaked
                }

                // `unsupported-protocol-version`, `unauthorized` or `malformed`.
                // All three are permanent: the Worker already authorised the
                // upgrade (L3), so a refusal here is this build disagreeing with
                // the server about the protocol, and asking again loops on it.
                is ServerHelloError -> Step.Stop(SessionEnd.Permanent("hello: ${message.code}"))

                is ChangeMessage -> {
                    // F5.6: a seq past cursor + 1 is a gap, and the answer is a
                    // pull, never a skip. The applier decides; this only reacts.
                    if (applier.apply(message.change) == ApplyOutcome.GAP) {
                        engine.catchUp(listId)
                    }
                    Step.Continue
                }

                // Not asked for — the reconnect pulls over HTTP, which already
                // pages — but applying one that arrives is free and correct.
                is CatchUpResponse -> {
                    applier.noteServerMaxSeq(listId, message.maxSeq)
                    for (change in message.changes) applier.apply(change)
                    Step.Continue
                }

                // Nothing is ever mutated over this socket, so neither of these
                // answers a question this client asked. Dropped rather than
                // treated as an error: a server that sends one is not broken.
                is MutationAck, is ServerError -> Step.Continue
            }

        private suspend fun ended(
            event: SocketEvent.Down,
            handshaked: Boolean,
        ): SessionEnd =
            when {
                // Only ever seen on a refused upgrade: an expired access token,
                // which is normal on a long-lived socket. Refreshing here and
                // letting the reconnect carry the new one keeps the token
                // lifecycle in one place.
                event.status == HTTP_UNAUTHORIZED ->
                    if (tokens.refreshed() == null) {
                        SessionEnd.SessionGone
                    } else {
                        SessionEnd.Transient("unauthorized", handshaked)
                    }

                // Not a member. 403 rather than 404 on purpose — membership must
                // not double as an oracle for which list ids exist — so this says
                // nothing about whether the list is there.
                event.status == HTTP_FORBIDDEN -> SessionEnd.Permanent("forbidden")

                // Any other refusal of the upgrade itself is this client asking
                // wrongly, and asking again would be a hot loop.
                event.status != null && event.status < HTTP_SERVER_ERROR ->
                    SessionEnd.Permanent("upgrade refused: ${event.status}")

                else ->
                    SessionEnd.Transient(
                        if (event.cause == null) "closed" else "dropped",
                        handshaked,
                    )
            }
    }
