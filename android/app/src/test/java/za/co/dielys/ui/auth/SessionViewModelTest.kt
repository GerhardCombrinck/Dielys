package za.co.dielys.ui.auth

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.SessionStack

/**
 * The one screen that causes a network call. What matters here is what it says
 * back: registration may name the problem, signing in may not (L2, ADR 0004).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var phone: SessionStack
    private lateinit var viewModel: SessionViewModel

    private val goodPassword = "correct-horse-battery"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        phone = SessionStack(ApplicationProvider.getApplicationContext())
        viewModel = SessionViewModel(phone.sessions, phone.magicLinks)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `creating an account signs in and asks for a sync`() =
        runTest(dispatcher) {
            submit(AuthMode.SignUp, "friend@example.test", goodPassword)

            assertTrue(viewModel.signedIn.value)
            // A brand new device knows nothing, so it does not wait for the
            // half-hourly worker to find out (H3.12).
            assertEquals(1, phone.scheduler.requests)
            // The password does not outlive the attempt.
            assertEquals("", viewModel.form.value.password)
        }

    @Test
    fun `a taken email says so, because the server does`() =
        runTest(dispatcher) {
            phone.account("taken@example.test", goodPassword)

            submit(AuthMode.SignUp, "taken@example.test", goodPassword)

            assertFalse(viewModel.signedIn.value)
            assertEquals(
                "That email already has an account. Sign in instead.",
                viewModel.form.value.problem,
            )
        }

    /**
     * The mirror of the test above: the server answers `invalid-credentials` for
     * a wrong password, a wrong email and an account that does not exist alike,
     * and the screen must not be more helpful than that.
     */
    @Test
    fun `signing in never says whether the account exists`() =
        runTest(dispatcher) {
            submit(AuthMode.SignIn, "nobody@example.test", goodPassword)
            val missing = viewModel.form.value.problem

            phone.account("real@example.test", goodPassword)
            submit(AuthMode.SignIn, "real@example.test", "wrong-password-entirely")
            val wrongPassword = viewModel.form.value.problem

            assertEquals("Email or password is wrong.", missing)
            assertEquals(missing, wrongPassword)
        }

    @Test
    fun `a short password is refused before it is sent anywhere`() =
        runTest(dispatcher) {
            submit(AuthMode.SignUp, "friend@example.test", "short")

            assertEquals(emptyList<String>(), phone.sentEmails)
            assertEquals(
                "Use at least ${phone.sessions.minPasswordLength} characters.",
                viewModel.form.value.problem,
            )
        }

    @Test
    fun `too many attempts is its own answer, not a wrong password`() =
        runTest(dispatcher) {
            phone.refuseNextAsRateLimited()

            submit(AuthMode.SignUp, "friend@example.test", goodPassword)

            assertEquals("Too many attempts. Try again later.", viewModel.form.value.problem)
        }

    @Test
    fun `no connection is not a rejection`() =
        runTest(dispatcher) {
            phone.online = false

            submit(AuthMode.SignUp, "friend@example.test", goodPassword)

            assertEquals(
                "No connection. Try again when you have signal.",
                viewModel.form.value.problem,
            )
        }

    @Test
    fun `switching mode keeps what has been typed and drops the last complaint`() =
        runTest(dispatcher) {
            submit(AuthMode.SignIn, "friend@example.test", goodPassword)
            assertEquals("Email or password is wrong.", viewModel.form.value.problem)

            viewModel.onMode(AuthMode.SignUp)

            val form = viewModel.form.value
            assertEquals(AuthMode.SignUp, form.mode)
            assertEquals("friend@example.test", form.email)
            assertEquals(goodPassword, form.password)
            assertNull(form.problem)
        }

    @Test
    fun `signing out clears the form and the session`() =
        runTest(dispatcher) {
            submit(AuthMode.SignUp, "friend@example.test", goodPassword)

            viewModel.signOut()

            assertFalse(viewModel.signedIn.value)
            assertFalse(phone.sessions.isSignedIn())
            assertEquals(AuthMode.SignIn, viewModel.form.value.mode)
        }

    @Test
    fun `a tapped magic link signs in without a form submission`() =
        runTest(dispatcher) {
            phone.nextMagicLinkIsFor("friend@example.test")

            phone.magicLinks.offer("https://dielys.com/magic?token=abc123XYZ-_")

            assertTrue(viewModel.signedIn.value)
            assertEquals(1, phone.scheduler.requests)
        }

    @Test
    fun `a spent or expired link says so, and does not sign in`() =
        runTest(dispatcher) {
            // No `nextMagicLinkIsFor` set — FakeAuthApi.verifyMagicLink answers
            // invalid-token, same as a token nothing on the server recognises.
            phone.magicLinks.offer("https://dielys.com/magic?token=abc123XYZ-_")

            assertFalse(viewModel.signedIn.value)
            assertEquals(
                "That link is no longer valid. Request a new one.",
                viewModel.form.value.problem,
            )
        }

    @Test
    fun `an unrelated link is ignored`() =
        runTest(dispatcher) {
            phone.magicLinks.offer("dielys://invite?t=header.payload.signature")

            assertFalse(viewModel.signedIn.value)
            assertNull(viewModel.form.value.problem)
        }

    private fun submit(
        mode: AuthMode,
        email: String,
        password: String,
    ) {
        viewModel.onMode(mode)
        viewModel.onEmail(email)
        viewModel.onPassword(password)
        viewModel.submit()
    }
}
