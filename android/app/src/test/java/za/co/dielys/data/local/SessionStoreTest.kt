package za.co.dielys.data.local

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The parts of the session that outlive a sign-out.
 *
 * Preferences rather than the database because they must survive a schema problem
 * the database might not, and Robolectric is what supplies the `Context` they need
 * without an emulator (H1).
 */
@RunWith(RobolectricTestRunner::class)
class SessionStoreTest {
    private val store = SessionStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `the device id is minted once and then never changes`() {
        val first = store.deviceId

        assertNotNull(first)
        assertEquals(first, store.deviceId)
        assertEquals(first, SessionStore(ApplicationProvider.getApplicationContext()).deviceId)
    }

    /**
     * The device id and the FCM token both belong to the phone, not to the account,
     * so neither is cleared. What does go is the record that the server was told:
     * the server files a push token under the device id, so the next account to sign
     * in here has to claim that row or it would keep being woken for the previous
     * account's lists (M2).
     */
    @Test
    fun `signing out keeps the device and its token but forgets the registration`() {
        val deviceId = store.deviceId
        store.accessToken = "access"
        store.refreshToken = "refresh"
        store.userId = "user-1"
        store.pushToken = "fcm-token-1"
        store.pushTokenSent = "fcm-token-1"

        store.clearSession()

        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertNull(store.userId)
        assertEquals(deviceId, store.deviceId)
        assertEquals("fcm-token-1", store.pushToken)
        assertNull(store.pushTokenSent)
    }
}
