package za.co.dielys.data.sync

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.FakePushTokens
import za.co.dielys.data.FixedDevice
import za.co.dielys.data.directExecutor
import za.co.dielys.data.inMemoryDatabase
import za.co.dielys.data.local.SyncStateEntity
import za.co.dielys.data.remote.ChangeEnvelope
import za.co.dielys.data.remote.ChangeMessage
import za.co.dielys.data.remote.DielysJson
import za.co.dielys.data.remote.ErrorCode
import za.co.dielys.data.remote.PROTOCOL_VERSION
import za.co.dielys.data.remote.ServerHelloError
import za.co.dielys.data.remote.ServerHelloOk
import za.co.dielys.data.remote.ServerMessage
import za.co.dielys.data.remote.TaskMutation
import za.co.dielys.data.remote.TaskPatch
import za.co.dielys.domain.Uuid7

private const val DEVICE = "device-a"
private const val OTHER = "device-b"

/**
 * What the socket does, with a hand on both ends of it.
 *
 * The rule underneath every one of these is the same: the socket is a latency
 * optimisation, and nothing it gets wrong may cost a change. So the assertions are
 * mostly about what happens *anyway* — a gap pulls, a dead connection reconnects,
 * an unreadable message is ignored rather than fatal.
 *
 * Room runs on [directExecutor] here because these assert on when an emission
 * happens, not only that it does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SocketSessionTest {
    private val api = FakeSyncApi()
    private val db = inMemoryDatabase(directExecutor)
    private val applier = ChangeApplier(db)
    private val engine = SyncEngine(db, api, applier, FakePushTokens())
    private val sockets = FakeListSockets()
    private val tokens = FakeTokens()
    private val session =
        ListSocketSession(sockets, tokens, FixedDevice(DEVICE), db, applier, engine)

    private val listId = Uuid7.generate()

    @After
    fun close() = db.close()

    @Test
    fun `the handshake carries the cursor this device has actually applied`() =
        runTest {
            db.syncState().upsert(SyncStateEntity(listId, cursor = 3L, serverMaxSeq = 3L))

            val run = async(start = CoroutineStart.UNDISPATCHED) { session.run(listId) }
            val socket = requireNotNull(sockets.last(listId))
            // The handshake is a suspend read of the cursor, so let it finish.
            runCurrent()

            val hello = DielysJson.wire.parseToJsonElement(socket.messages().single())
            assertEquals(listId, hello.field("listId"))
            assertEquals(DEVICE, hello.field("deviceId"))
            assertEquals("3", hello.field("cursor"))
            assertEquals(PROTOCOL_VERSION.toString(), hello.field("protocolVersion"))

            // The upgrade carries the token, not the hello (L3): the Worker has to
            // authorise before anything reaches the Durable Object, and it never
            // sees a frame sent after the socket is open.
            assertEquals("access-token", socket.token)
            assertEquals(DEVICE, socket.deviceId)

            socket.drop()
            run.await()
        }

    @Test
    fun `a change arriving on the socket lands in Room and moves the cursor`() =
        runTest {
            val run = async(start = CoroutineStart.UNDISPATCHED) { session.run(listId) }
            val socket = requireNotNull(sockets.last(listId))
            // The handshake is a suspend read of the cursor, so let it finish.
            runCurrent()

            socket.say(change(theOtherDevice("Milk")))
            runCurrent()

            assertEquals(listOf("Milk"), titles())
            assertEquals(1L, db.syncState().cursor(listId))
            // Never a mutation. The outbox drains over HTTP so that it still works
            // with the app not running (H3.4); a second write path here would be a
            // second place for F5.2 to be wrong.
            assertEquals(1, socket.messages().size)

            socket.drop()
            run.await()
        }

    /** F5.6 over the socket: a seq past `cursor + 1` is pulled, never skipped. */
    @Test
    fun `a seq gap on the socket pulls the range instead of skipping it`() =
        runTest {
            val first = theOtherDevice("Milk")
            val second = theOtherDevice("Eggs")

            val run = async(start = CoroutineStart.UNDISPATCHED) { session.run(listId) }
            val socket = requireNotNull(sockets.last(listId))
            // The handshake is a suspend read of the cursor, so let it finish.
            runCurrent()

            // Only the second arrives. The first was on a socket that was not open.
            socket.say(change(second))
            runCurrent()

            assertEquals(setOf("Milk", "Eggs"), titles().toSet())
            assertEquals(second.seq, db.syncState().cursor(listId))
            assertEquals(first.seq + 1, second.seq)

            socket.drop()
            run.await()
        }

    @Test
    fun `hello-ok says the server is ahead and the catch-up runs without waiting`() =
        runTest {
            theOtherDevice("Milk")
            theOtherDevice("Eggs")

            val run = async(start = CoroutineStart.UNDISPATCHED) { session.run(listId) }
            val socket = requireNotNull(sockets.last(listId))
            // The handshake is a suspend read of the cursor, so let it finish.
            runCurrent()

            socket.say(helloOk(maxSeq = 2L))
            runCurrent()

            assertEquals(2, titles().size)
            assertEquals(2L, db.syncState().cursor(listId))

            socket.drop()
            run.await()
        }

    /** H3.11. A socket that has quietly died looks exactly like a quiet one. */
    @Test
    fun `a ping that goes unanswered closes the socket and ends the session`() =
        runTest {
            val run = async(start = CoroutineStart.UNDISPATCHED) { session.run(listId) }
            val socket = requireNotNull(sockets.last(listId))
            socket.say(helloOk(maxSeq = 0L))
            runCurrent()
            socket.answerPings = false

            advanceTimeBy(SOCKET_PING_MILLIS + 1)
            runCurrent()
            assertTrue("the first ping should have gone out", socket.sent.contains("ping"))
            assertFalse("one missed pong is not enough", socket.closed.isCompleted)

            advanceTimeBy(SOCKET_PING_MILLIS)
            runCurrent()

            assertTrue("the socket should have been closed", socket.closed.isCompleted)
            val end = run.await()
            // Handshaked, so the reconnect starts from the floor delay rather than
            // wherever a flapping connection had got to.
            assertEquals(SessionEnd.Transient("closed", handshaked = true), end)
        }

    /** F2. An old client meeting a newer server degrades; it does not fall over. */
    @Test
    fun `a message this build cannot read is ignored and the socket survives`() =
        runTest {
            val run = async(start = CoroutineStart.UNDISPATCHED) { session.run(listId) }
            val socket = requireNotNull(sockets.last(listId))
            // The handshake is a suspend read of the cursor, so let it finish.
            runCurrent()

            socket.say("""{"type":"something-a-later-version-sends","payload":{"a":1}}""")
            socket.say("not json at all")
            runCurrent()

            socket.say(change(theOtherDevice("Milk")))
            runCurrent()

            assertEquals(listOf("Milk"), titles())

            socket.drop()
            run.await()
        }

    @Test
    fun `a refused handshake stops this list rather than retrying it`() =
        runTest {
            val run = async(start = CoroutineStart.UNDISPATCHED) { session.run(listId) }
            val socket = requireNotNull(sockets.last(listId))
            // The handshake is a suspend read of the cursor, so let it finish.
            runCurrent()

            socket.say(
                DielysJson.wire.encodeToString(
                    ServerMessage.serializer(),
                    ServerHelloError(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION),
                ),
            )

            val end = run.await()
            assertTrue(end.toString(), end is SessionEnd.Permanent)
            assertTrue(socket.closed.isCompleted)
        }

    @Test
    fun `an expired access token is refreshed and the reconnect carries the new one`() =
        runTest {
            sockets.refuseWith = 401

            val end = session.run(listId)

            assertEquals(1, tokens.refreshes)
            assertEquals(SessionEnd.Transient("unauthorized", handshaked = false), end)

            sockets.refuseWith = null
            val run = async(start = CoroutineStart.UNDISPATCHED) { session.run(listId) }
            assertEquals("refreshed-access-token", requireNotNull(sockets.last(listId)).token)
            requireNotNull(sockets.last(listId)).drop()
            run.await()
        }

    /**
     * 403, not 404 — membership must not double as an oracle for which list ids
     * exist — so this says nothing about whether the list is there, only that
     * asking again would not help.
     */
    @Test
    fun `a list this account cannot reach is not retried`() =
        runTest {
            sockets.refuseWith = 403

            val end = session.run(listId)

            assertEquals(0, tokens.refreshes)
            assertTrue(end.toString(), end is SessionEnd.Permanent)
        }

    @Test
    fun `no socket is opened at all once the refresh token is dead`() =
        runTest {
            val dead = FakeTokens(token = null, refreshesTo = null)
            val gone =
                ListSocketSession(sockets, dead, FixedDevice(DEVICE), db, applier, engine)

            assertEquals(SessionEnd.SessionGone, gone.run(listId))
            assertNull(sockets.last(listId))
        }

    private suspend fun titles(): List<String> = db.tasks().inList(listId).map { it.title }

    /** A change the other phone made, already on the server. */
    private fun theOtherDevice(title: String): ChangeEnvelope =
        api.otherDevice(
            TaskMutation(
                listId = listId,
                idempotencyKey = Uuid7.generate(),
                deviceId = OTHER,
                entityId = Uuid7.generate(),
                patch = TaskPatch(title = title, position = "a$title"),
            ),
        )

    private fun change(envelope: ChangeEnvelope): String =
        DielysJson.wire.encodeToString(ServerMessage.serializer(), ChangeMessage(envelope))

    private fun helloOk(maxSeq: Long): String =
        DielysJson.wire.encodeToString(
            ServerMessage.serializer(),
            ServerHelloOk(PROTOCOL_VERSION, listId, maxSeq),
        )
}

private fun JsonElement.field(name: String): String =
    jsonObject.getValue(name).jsonPrimitive.content
