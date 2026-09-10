package za.co.dielys.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unlike [InviteLinkTest], this arrives as a real App Link (ADR 0005) — no
 * "pasted into a chat" case to be forgiving about, but it does need to ignore
 * anything that is not this exact host and path, including an invite link.
 */
class MagicLinkUrlTest {
    private val token = "5wQ9x2NqYw3fJ8dK1z0mR4tV7bC6sL_uA-P2eH9jN0"

    @Test
    fun `a link round-trips`() {
        val url = "https://dielys.com/magic?token=$token"
        assertEquals(token, MagicLinkUrl.tokenFrom(url))
    }

    @Test
    fun `ignores an unrelated host or path`() {
        assertNull(MagicLinkUrl.tokenFrom("https://evil.example/magic?token=$token"))
        assertNull(MagicLinkUrl.tokenFrom("https://dielys.com/not-magic?token=$token"))
    }

    @Test
    fun `ignores an invite link`() {
        assertNull(MagicLinkUrl.tokenFrom("dielys://invite?t=header.payload.signature"))
    }

    @Test
    fun `text with no token in it is not a magic link`() {
        assertNull(MagicLinkUrl.tokenFrom(""))
        assertNull(MagicLinkUrl.tokenFrom("https://dielys.com/magic"))
    }
}
