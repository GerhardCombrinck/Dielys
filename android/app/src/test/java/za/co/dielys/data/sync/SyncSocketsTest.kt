package za.co.dielys.data.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.FakePushTokens
import za.co.dielys.data.FixedDevice
import za.co.dielys.data.directExecutor
import za.co.dielys.data.inMemoryDatabase
import za.co.dielys.data.local.ListEntity
import za.co.dielys.data.local.SessionSignal

/**
 * Which lists have a socket, and when they stop having one.
 *
 * None of this is required for a change to arrive — the catch-up pull is what
 * makes that true (F5.6) — so the assertions are about not holding connections
 * nobody is looking at, and about letting go of them the moment there is no
 * session behind them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncSocketsTest {
    private val api = FakeSyncApi()
    private val db = inMemoryDatabase(directExecutor)
    private val applier = ChangeApplier(db)
    private val engine = SyncEngine(db, api, applier, FakePushTokens())
    private val sockets = FakeListSockets()
    private val signal = FakeSessionSignal()
    private val session =
        ListSocketSession(sockets, FakeTokens(), FixedDevice("device-a"), db, applier, engine)

    @After
    fun close() = db.close()

    @Test
    fun `one socket per list, and none at all with nobody looking`() =
        runTest {
            know("list-a", "list-b")
            val supervisor = SyncSockets(session, db, signal, backgroundScope)
            signal.set(true)

            supervisor.start()
            runCurrent()
            assertEquals(emptyList<String>(), sockets.opened.map { it.listId })

            supervisor.onForeground()
            runCurrent()
            assertEquals(listOf("list-a", "list-b"), sockets.opened.map { it.listId })
        }

    /**
     * The list somebody else created and invited this account to. Nothing tells
     * the supervisor about it: discovery writes the row and the query emits.
     */
    @Test
    fun `a list that turns up later gets a socket without being announced`() =
        runTest {
            know("list-a")
            val supervisor = signedInAndLooking()

            know("list-b")
            runCurrent()

            assertEquals(listOf("list-a", "list-b"), sockets.opened.map { it.listId })
            assertFalse(requireNotNull(sockets.last("list-a")).closed.isCompleted)
            supervisor.onBackground()
        }

    @Test
    fun `signing out closes every socket at once`() =
        runTest {
            know("list-a", "list-b")
            signedInAndLooking()

            signal.set(false)
            runCurrent()

            assertTrue(sockets.opened.all { it.closed.isCompleted })
        }

    /**
     * A rotation takes the only Activity down and puts it straight back up. Doing
     * that to every socket on every rotation would be a reconnect and a catch-up
     * pull for nothing.
     */
    @Test
    fun `a rotation does not cost the socket, but pocketing the phone does`() =
        runTest {
            know("list-a")
            val supervisor = signedInAndLooking()
            val socket = requireNotNull(sockets.last("list-a"))

            supervisor.onBackground()
            supervisor.onForeground()
            advanceTimeBy(SETTLED_MILLIS)
            runCurrent()

            assertFalse("the rotation should have been ridden out", socket.closed.isCompleted)
            assertEquals(1, sockets.opened.size)

            supervisor.onBackground()
            advanceTimeBy(SETTLED_MILLIS)
            runCurrent()

            assertTrue("going away for good closes it", socket.closed.isCompleted)
        }

    private suspend fun know(vararg ids: String) {
        for (id in ids) db.lists().upsert(ListEntity(id = id, title = "", role = null))
    }

    private fun TestScope.signedInAndLooking(): SyncSockets {
        val supervisor = SyncSockets(session, db, signal, backgroundScope)
        signal.set(true)
        supervisor.start()
        supervisor.onForeground()
        runCurrent()
        return supervisor
    }

    private companion object {
        /** Comfortably past the grace period the supervisor holds a going-dark for. */
        const val SETTLED_MILLIS = 5_000L
    }
}

/** Signed in or not, movable from a test. */
class FakeSessionSignal : SessionSignal {
    private val state = MutableStateFlow(false)

    override val signedIn: StateFlow<Boolean> = state.asStateFlow()

    fun set(value: Boolean) {
        state.value = value
    }
}
