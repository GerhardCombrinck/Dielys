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
 * An `https://` App Link, same as [MagicLinkUrl] — the domain verification and
 * fallback page (`GET /invite`) that ADR 0005 built for the magic-link sign-in
 * link cover this path too, `handle_all_urls` in assetlinks.json being
 * domain-wide rather than per-path. Sharing still goes through a plain share
 * sheet rather than email specifically (see `ShareDialogs.shareInvite`), so an
 * `https://` link matters even for chat apps: several of them, Gmail's Android
 * app among them, do not reliably make a custom-scheme link tappable out of
 * HTML mail, which is why pasting into [za.co.dielys.ui.lists.JoinDialog] still
 * has to keep working as the fallback.
 *
 * No `android.net.Uri` here on purpose (E1.1): the shape is fixed and the parsing
 * is worth testing on the JVM.
 */
object InviteLink {
    const val HOST: String = "dielys.com"
    const val PATH: String = "/invite"

    private const val PREFIX = "https://$HOST$PATH?t="

    /** A JWT is three base64url segments separated by dots. */
    private val TOKEN = Regex("""[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

    fun url(token: String): String = PREFIX + token

    /**
     * The token inside whatever was pasted, or null if there is not one.
     *
     * Matching the JWT shape rather than splitting on the prefix is what makes a
     * pasted *message* work as well as a pasted link — and a message is what
     * arrives, because the share sheet sends words around the link. It also
     * means an invite minted before this link became `https://` still parses,
     * with no separate case for the old `dielys://invite?t=` shape.
     */
    fun tokenFrom(text: String): String? = TOKEN.find(text.trim())?.value
}
