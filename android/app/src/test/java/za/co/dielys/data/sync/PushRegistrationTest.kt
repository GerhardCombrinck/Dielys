package za.co.dielys.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack

/**
 * Getting the FCM token to the server (M2).
 *
 * It rides the sync run rather than being sent from wherever it arrived, so it
 * inherits `WorkManager`'s backoff and the half-hourly floor. That makes the two
 * interesting cases the ordinary offline ones: a token that turns up in a dead spot
 * must not be lost, and a token the server refuses must not become a per-sync hot
 * loop.
 */
@RunWith(RobolectricTestRunner::class)
class PushRegistrationTest {
    private val api = FakeSyncApi()
    private val devices = mutableListOf<DeviceStack>()

    @After
    fun close() = devices.forEach { it.close() }

    @Test
    fun `the token is sent once and not again`() =
        runTest {
            val alice = device("device-a")
            alice.push.pushToken = "fcm-token-1"

            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(listOf("fcm-token-1"), api.pushTokens)
            assertEquals("fcm-token-1", alice.push.pushTokenSent)

            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(listOf("fcm-token-1"), api.pushTokens)
        }

    @Test
    fun `a device FCM has not given a token says nothing`() =
        runTest {
            val alice = device("device-a")

            assertEquals(SyncOutcome.Success, alice.engine.sync())

            assertEquals(emptyList<String>(), api.pushTokens)
            assertNull(alice.push.pushTokenSent)
        }

    /** H3.1, for the registration: offline is a delay, never a loss. */
    @Test
    fun `a token that arrives with no network is sent on the next run`() =
        runTest {
            val alice = device("device-a")
            alice.push.pushToken = "fcm-token-1"
            api.online = false

            val offline = alice.engine.registerPushToken()

            assertTrue(offline is SyncOutcome.Retry)
            assertNull(alice.push.pushTokenSent)

            api.online = true
            assertEquals(SyncOutcome.Success, alice.engine.registerPushToken())
            assertEquals(listOf("fcm-token-1"), api.pushTokens)
        }

    /**
     * A refused token is recorded as sent so it is not offered again on every sync
     * — the same call the outbox makes when it marks a row dead. The next token FCM
     * issues is a fresh attempt.
     */
    @Test
    fun `a refused token stops being retried, and the next one is not punished for it`() =
        runTest {
            val alice = device("device-a")
            alice.push.pushToken = "fcm-token-1"
            api.rejectPushToken = true

            assertEquals(SyncOutcome.Success, alice.engine.registerPushToken())
            assertEquals(emptyList<String>(), api.pushTokens)
            assertEquals("fcm-token-1", alice.push.pushTokenSent)

            assertEquals(SyncOutcome.Success, alice.engine.registerPushToken())
            assertEquals(emptyList<String>(), api.pushTokens)

            api.rejectPushToken = false
            alice.push.pushToken = "fcm-token-2"
            assertEquals(SyncOutcome.Success, alice.engine.registerPushToken())
            assertEquals(listOf("fcm-token-2"), api.pushTokens)
        }

    /**
     * Registration is the last step for a reason: a device that cannot register is
     * late to hear about changes, while a device that cannot drain its outbox is
     * holding edits nobody else can see.
     */
    @Test
    fun `a failed drain does not send the token`() =
        runTest {
            val alice = device("device-a")
            val listId = alice.repo.createList("Groceries")
            alice.push.pushToken = "fcm-token-1"
            api.online = false

            assertTrue(alice.engine.sync() is SyncOutcome.Retry)
            assertEquals(emptyList<String>(), api.pushTokens)

            api.online = true
            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(listOf("fcm-token-1"), api.pushTokens)
            assertEquals(listOf(listId), alice.db.lists().knownIds())
        }

    private fun device(id: String): DeviceStack = DeviceStack(api, id).also { devices += it }
}
