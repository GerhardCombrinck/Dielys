package za.co.dielys.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import za.co.dielys.data.remote.MembershipRole

/** Who may delete a list, as the phone decides whether to offer it (ADR 0006). */
class ListEntityTest {
    private fun list(role: String?) = ListEntity(id = "list-1", title = "Inkopies", role = role)

    @Test
    fun `the owner may delete, a member may not`() {
        assertTrue(list(MembershipRole.OWNER).mayDelete)
        assertFalse(list(MembershipRole.MEMBER).mayDelete)
    }

    /**
     * A list that arrived by invite has its role from the start, so an unknown
     * role is a list made here and not synced yet — owned by construction.
     */
    @Test
    fun `a list made on this phone and not yet synced may be deleted`() {
        assertTrue(list(role = null).mayDelete)
    }
}
