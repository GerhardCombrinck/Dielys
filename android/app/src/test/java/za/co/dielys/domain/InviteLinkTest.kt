package za.co.dielys.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whatever gets pasted has been through a chat app first, so this is mostly
 * about what survives that trip: a link, a link with a sentence around it, or a
 * link a messenger decided not to linkify.
 */
class InviteLinkTest {
    private val token = "eyJhbGciOiJIUzI1NiJ9.eyJsaXN0SWQiOiJhYmMifQ.c2lnbmF0dXJl"

    @Test
    fun `a link round-trips`() {
        assertEquals(token, InviteLink.tokenFrom(InviteLink.url(token)))
    }

    @Test
    fun `the link is an https App Link on the shared domain`() {
        assertEquals("https://dielys.com/invite?t=$token", InviteLink.url(token))
    }

    @Test
    fun `an invite minted before the App Link switch still parses`() {
        assertEquals(token, InviteLink.tokenFrom("dielys://invite?t=$token"))
    }

    @Test
    fun `the token is found inside a whole shared message`() {
        val shared = "Join \"Groceries\" on Dielys: ${InviteLink.url(token)}"
        assertEquals(token, InviteLink.tokenFrom(shared))
    }

    @Test
    fun `a bare token pasted on its own is taken as-is`() {
        assertEquals(token, InviteLink.tokenFrom("  $token \n"))
    }

    @Test
    fun `text with no token in it is not an invite`() {
        assertNull(InviteLink.tokenFrom("are you free on saturday"))
        assertNull(InviteLink.tokenFrom(""))
        // Two segments is a JWS header and payload with the signature lost in
        // the paste. Not something to send to the server hopefully.
        assertNull(InviteLink.tokenFrom("eyJhbGciOiJIUzI1NiJ9.eyJsaXN0SWQiOiJhYmMifQ"))
    }
}
