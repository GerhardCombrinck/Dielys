package za.co.dielys.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.local.SessionStore
import za.co.dielys.data.remote.ApiException
import za.co.dielys.data.remote.ErrorCode
import za.co.dielys.data.remote.FakeAuthApi

/**
 * When a refresh ends the session, and when it must not.
 *
 * A refresh token is single-use (L1). Throwing one away over a blip, or losing
 * the one the server just rotated to, is what made the app sign people out at
 * random (ADR 0013).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionRepositoryTest {
    private val auth = FakeAuthApi().apply { accounts[EMAIL] = PASSWORD }
    private val store =
        SessionStore(ApplicationProvider.getApplicationContext()).apply {
            // Preferences outlive a Robolectric test, so each one starts clean.
            clearSession()
        }
    private val sessions = SessionRepository(store, auth, RecordingScheduler())

    @Test
    fun `a rate-limited refresh keeps the session`() =
        runTest {
            sessions.signIn(EMAIL, PASSWORD)
            auth.rejectWith = ErrorCode.RATE_LIMITED

            val error = runCatching { sessions.refreshed() }.exceptionOrNull()

            assertTrue(error is ApiException.Unavailable)
            assertTrue(sessions.isSignedIn())
            // The same token is still good once the server lets it through.
            assertEquals("access-2", sessions.refreshed())
        }

    @Test
    fun `a refresh token the server rejects ends the session`() =
        runTest {
            sessions.signIn(EMAIL, PASSWORD)
            auth.rejectWith = ErrorCode.TOKEN_EXPIRED

            assertNull(sessions.refreshed())
            assertFalse(sessions.isSignedIn())
        }

    @Test
    fun `a refresh cancelled mid-request still keeps what the server sent`() =
        runTest {
            sessions.signIn(EMAIL, PASSWORD)
            val answer = CompletableDeferred<Unit>()
            auth.holdRefresh = answer

            // The socket that asked closes as the app goes to the background,
            // after the server has already rotated the token.
            val caller = launch { sessions.refreshed() }
            runCurrent()
            caller.cancel()
            answer.complete(Unit)
            caller.join()

            assertEquals("refresh-2", store.refreshToken)
            assertEquals("access-2", sessions.current())
        }

    private companion object {
        const val EMAIL = "gerhard@dielys.test"
        const val PASSWORD = "a-generated-password"
    }
}
