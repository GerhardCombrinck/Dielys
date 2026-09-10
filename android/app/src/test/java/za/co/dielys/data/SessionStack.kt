package za.co.dielys.data

import android.content.Context
import za.co.dielys.data.local.SessionStore
import za.co.dielys.data.remote.ErrorCode
import za.co.dielys.data.remote.FakeAuthApi

/**
 * One phone's session: real preferences, a real repository, a fake server.
 *
 * It exists so a screen's test can drive registration and sign-in without
 * importing anything from `data.remote` — the layer rule in E1.2 is checked
 * against test sources too, and a test that has to break it is a test that has
 * reached past the seam it is supposed to be using.
 */
class SessionStack(
    context: Context,
) {
    val scheduler = RecordingScheduler()

    private val auth = FakeAuthApi()
    private val store =
        SessionStore(context).apply {
            // Preferences outlive a Robolectric test, so each stack starts clean.
            clearSession()
        }

    val sessions = SessionRepository(store, auth, scheduler)

    /** A magic link waiting to be redeemed, the same singleton a real
     * `MainActivity` would park an incoming App Link token in (ADR 0005). */
    val magicLinks = PendingMagicLink()

    /** Flip to false to make every call fail the way no network fails. */
    var online: Boolean
        get() = auth.online
        set(value) {
            auth.online = value
        }

    /** Every email the repository actually sent, in order. */
    val sentEmails: List<String> get() = auth.seen

    /** Every email a magic link was requested for, in order. */
    val sentMagicLinks: List<String> get() = auth.magicLinkRequests

    /** An account that already exists, for signing in to or colliding with. */
    fun account(
        email: String,
        password: String,
    ) {
        auth.accounts[email] = password
    }

    /** The next `verifyMagicLink` call succeeds and resolves to this email —
     * standing in for the server looking up the token's row (ADR 0005). */
    fun nextMagicLinkIsFor(email: String) {
        auth.magicLinkAccount = email
    }

    /** Answers the next call the way the server answers a flooded bucket (ADR 0004). */
    fun refuseNextAsRateLimited() {
        auth.rejectWith = ErrorCode.RATE_LIMITED
    }

    /** Makes `magicLinkStatus` report delivered for the most recently
     * requested link — standing in for Brevo's event report catching up
     * (ADR 0005 follow-up). */
    fun markMagicLinkDelivered() {
        auth.delivered = true
    }

    /** Every requestId `magicLinkStatus` was polled with, in order. */
    val statusChecks: List<String> get() = auth.statusChecks
}
