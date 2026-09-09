package za.co.dielys.data.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import za.co.dielys.Fixtures
import za.co.dielys.data.FakePushTokens
import za.co.dielys.data.RecordingScheduler

/**
 * The wake path, without a `Service`.
 *
 * The rule being defended is M1: what arrives over FCM is a hint that something
 * changed and never the thing that changed. The parser is the enforcement point —
 * it accepts three keys and would have nothing to hand on if a fourth turned up
 * carrying a task title.
 */
class PushHandlerTest {
    private val scheduler = RecordingScheduler()
    private val push = FakePushTokens()
    private val handler = PushHandler(scheduler, push)

    @Test
    fun `the fixture the server sends parses, and carries nothing else`() {
        val payload: Map<String, String> = Fixtures.load("push/sync-wake.json")

        assertEquals(setOf("type", "listId", "seq"), payload.keys)
        assertEquals(
            WakeHint("01936b2a-4c3d-7e8f-9a0b-1c2d3e4f5a6b", 7L),
            WakeHint.from(payload),
        )
    }

    @Test
    fun `a wake asks for a sync and nothing more`() {
        assertTrue(handler.onMessage(wake()))

        assertEquals(1, scheduler.requests)
        // Not touched: a push says nothing about registration (M2).
        assertNull(push.pushToken)
        assertNull(push.pushTokenSent)
    }

    @Test
    fun `a payload this build does not recognise is ignored`() {
        assertFalse(handler.onMessage(wake(type = "promo")))
        assertFalse(handler.onMessage(wake(listId = "")))
        assertFalse(handler.onMessage(wake(seq = "soon")))
        assertFalse(handler.onMessage(emptyMap()))

        assertEquals(0, scheduler.requests)
    }

    @Test
    fun `a new token is stored and handed to the next sync run`() {
        handler.onToken("token-1")

        assertEquals("token-1", push.pushToken)
        // Registration is the sync engine's job: onNewToken can fire with no
        // network and no session (M2).
        assertNull(push.pushTokenSent)
        assertEquals(1, scheduler.requests)
    }

    @Test
    fun `a token that has not changed costs nothing`() {
        handler.onToken("token-1")
        handler.onToken("token-1")

        assertEquals(1, scheduler.requests)
    }

    /**
     * FCM re-issuing a token has to reach the server, or this phone keeps being
     * woken through a registration it no longer holds.
     */
    @Test
    fun `a rotated token leaves the confirmed value behind so the engine resends`() {
        handler.onToken("token-1")
        push.pushTokenSent = "token-1"

        handler.onToken("token-2")

        assertEquals("token-2", push.pushToken)
        assertEquals("token-1", push.pushTokenSent)
    }

    private fun wake(
        type: String = PUSH_TYPE_SYNC,
        listId: String = "01936b2a-4c3d-7e8f-9a0b-1c2d3e4f5a6b",
        seq: String = "7",
    ): Map<String, String> = mapOf("type" to type, "listId" to listId, "seq" to seq)
}
