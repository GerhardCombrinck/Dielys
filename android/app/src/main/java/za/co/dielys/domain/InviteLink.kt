package za.co.dielys.domain

/**
 * How an invite travels between two phones.
 *
 * The invite itself is a JWT the server minted (L3) — long, and not something
 * anybody is going to read out loud. So it goes in a link the sender shares and
 * the recipient taps, and the parser below is deliberately forgiving about what
 * comes back: a whole shared message, a bare link, or just the token, because a
 * person pasting from a chat app will produce any of the three.
 *
 * A custom scheme rather than an `https://` app link: an app link needs a
 * verified domain and a page to serve, and this app has neither yet. The cost is
 * that some chat apps will not turn it into a tappable link, which is why
 * pasting has to work at all.
 *
 * No `android.net.Uri` here on purpose (E1.1): the shape is fixed and the parsing
 * is worth testing on the JVM.
 */
object InviteLink {
    const val SCHEME: String = "dielys"
    const val HOST: String = "invite"

    private const val PREFIX = "$SCHEME://$HOST?t="

    /** A JWT is three base64url segments separated by dots. */
    private val TOKEN = Regex("""[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

    fun url(token: String): String = PREFIX + token

    /**
     * The token inside whatever was pasted, or null if there is not one.
     *
     * Matching the JWT shape rather than splitting on the prefix is what makes a
     * pasted *message* work as well as a pasted link — and a message is what
     * arrives, because the share sheet sends words around the link.
     */
    fun tokenFrom(text: String): String? = TOKEN.find(text.trim())?.value
}
