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
 * The one screen that causes a network call. There is no password and no
 * separate sign-up (ADR 0005): requesting a link is the only thing the form
 * does, and the account is created server-side on first redeem.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var phone: SessionStack
    private lateinit var viewModel: SessionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        phone = SessionStack(ApplicationProvider.getApplicationContext())
        viewModel = SessionViewModel(phone.sessions, phone.magicLinks)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `requesting a link sends the email and asks to check it`() =
        runTest(dispatcher) {
            submit("friend@example.test")

            assertEquals(listOf("friend@example.test"), phone.sentMagicLinks)
            assertTrue(viewModel.form.value.linkSent)
            // Nothing is signed in yet — only tapping the mailed link does that.
            assertFalse(viewModel.signedIn.value)
            assertNull(viewModel.form.value.problem)
        }

    @Test
    fun `too many attempts is its own answer`() =
        runTest(dispatcher) {
            phone.refuseNextAsRateLimited()

            submit("friend@example.test")

            assertEquals("Too many attempts. Try again later.", viewModel.form.value.problem)
            assertFalse(viewModel.form.value.linkSent)
        }

    @Test
    fun `no connection is not a rejection`() =
        runTest(dispatcher) {
            phone.online = false

            submit("friend@example.test")

            assertEquals(
                "No connection. Try again when you have signal.",
                viewModel.form.value.problem,
            )
        }

    @Test
    fun `a blank email is refused before it is sent anywhere`() =
        runTest(dispatcher) {
            submit("")

            assertEquals(emptyList<String>(), phone.sentMagicLinks)
        }

    @Test
    fun `editing the email after a link is sent returns to the form`() =
        runTest(dispatcher) {
            submit("friend@example.test")
            assertTrue(viewModel.form.value.linkSent)

            viewModel.onEmail("someone-else@example.test")

            val form = viewModel.form.value
            assertFalse(form.linkSent)
            assertEquals("someone-else@example.test", form.email)
            assertNull(form.problem)
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

    @Test
    fun `signing out clears the form and the session`() =
        runTest(dispatcher) {
            phone.nextMagicLinkIsFor("friend@example.test")
            submit("friend@example.test")
            phone.magicLinks.offer("https://dielys.com/magic?token=abc123XYZ-_")
            assertTrue(viewModel.signedIn.value)

            viewModel.signOut()

            assertFalse(viewModel.signedIn.value)
            assertFalse(phone.sessions.isSignedIn())
            assertEquals("", viewModel.form.value.email)
            assertFalse(viewModel.form.value.linkSent)
        }

    private fun submit(email: String) {
        viewModel.onEmail(email)
        viewModel.submit()
    }
}
