package za.co.dielys.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.local.SessionStore
import za.co.dielys.data.sync.FakeSyncApi

/**
 * Deleting the account (ADR 0007): the server is asked first, and only once it
 * has said yes does this phone let go of the session and wipe its replica.
 */
@RunWith(RobolectricTestRunner::class)
class AccountRepositoryTest {
    private val api = FakeSyncApi()
    private val device = DeviceStack(api, "device-a")
    private val store =
        SessionStore(ApplicationProvider.getApplicationContext()).apply {
            accessToken = "access"
            refreshToken = "refresh"
            userId = "user-1"
            email = "gerhard@dielys.test"
        }
    private val account = AccountRepository(api, store, device.db)

    @After
    fun close() = device.close()

    @Test
    fun `deletes on the server, then ends the session and empties the phone`() =
        runTest {
            val listId = device.repo.createList("Inkopies")
            device.repo.addTask(listId, "Melk")

            assertEquals(DeleteAccountResult.Deleted, account.deleteAccount())

            assertEquals(1, api.accountDeletions)
            assertNull(store.refreshToken)
            assertNull(store.email)
            assertTrue(
                device.db
                    .lists()
                    .knownIds()
                    .isEmpty(),
            )
            assertTrue(
                device.db
                    .tasks()
                    .inList(listId)
                    .isEmpty(),
            )
            assertTrue(
                device.db
                    .outbox()
                    .all()
                    .isEmpty(),
            )
        }

    @Test
    fun `offline, nothing is sent and nothing on the phone is touched`() =
        runTest {
            val listId = device.repo.createList("Inkopies")
            api.online = false

            assertEquals(DeleteAccountResult.Offline, account.deleteAccount())

            assertEquals(0, api.accountDeletions)
            assertEquals("refresh", store.refreshToken)
            assertEquals(listOf(listId), device.db.lists().knownIds())
        }

    @Test
    fun `a dead session is reported, and the phone keeps its data`() =
        runTest {
            val listId = device.repo.createList("Inkopies")
            api.unauthorized = true

            assertTrue(account.deleteAccount() is DeleteAccountResult.ServerProblem)

            assertEquals(listOf(listId), device.db.lists().knownIds())
        }
}
