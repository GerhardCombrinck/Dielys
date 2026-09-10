package za.co.dielys.domain

/**
 * How a passwordless sign-in link travels from a mailed link to a token the
 * app can redeem (ADR 0005, docs/adr/0005-passwordless-email-magic-link.md).
 *
 * Unlike [InviteLink], this arrives as a real `https://` App Link — Android
 * hands the whole URL to the app directly, so there is no "pasted message"
 * case to be forgiving about. No `android.net.Uri` here regardless (E1.1):
 * the shape is fixed and the parsing is worth testing on the JVM.
 */
object MagicLinkUrl {
    const val HOST: String = "dielys.com"
    const val PATH: String = "/magic"

    /**
     * A `generateRefreshToken()` output (server/src/auth/password.ts) is
     * unpadded base64url — `[A-Za-z0-9_-]+` — which needs no percent-decoding
     * even inside a query string, unlike the invite JWT's dots.
     */
    private val TOKEN_PARAM = Regex("""[?&]token=([A-Za-z0-9_-]+)""")

    /** The token in a magic-link URL, or null if this is not one. Ignores
     * anything whose host or path does not match, so an invite link handed
     * to this parser by mistake never falsely matches. */
    fun tokenFrom(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://$HOST$PATH")) return null
        return TOKEN_PARAM.find(trimmed)?.groupValues?.get(1)
    }
}
