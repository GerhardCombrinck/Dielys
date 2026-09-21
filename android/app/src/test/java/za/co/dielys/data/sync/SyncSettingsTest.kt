package za.co.dielys.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.DeviceStack
import za.co.dielys.data.remote.SyncSettings

/**
 * The background sync preference (ADR 0010) rides the end of every sync run.
 * It is one preference, not data: nothing about reconciling it may ever stop
 * the outbox from draining or a list from catching up.
 */
@RunWith(RobolectricTestRunner::class)
class SyncSettingsTest {
    private val api = FakeSyncApi()
    private val devices = mutableListOf<DeviceStack>()

    @After
    fun close() = devices.forEach { it.close() }

    /**
     * A Retry here would hold every later drain behind this run's backoff
     * (APPEND_OR_REPLACE), so edits made after it would sit in the outbox.
     */
    @Test
    fun `a failing settings route does not fail the run`() =
        runTest {
            val alice = device("device-a")
            val listId = alice.repo.createList("Groceries")
            api.syncSettingsDown = true

            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(listOf(listId), alice.db.lists().knownIds())
            assertEquals(0, alice.db.outbox().countForList(listId))
        }

    /** Unsynced, so the next run that reaches the server tries again. */
    @Test
    fun `a local change that could not be sent is sent on the next run`() =
        runTest {
            val alice = device("device-a")
            alice.engine.sync()
            alice.syncPrefs.setSyncEnabled(false)
            api.syncSettingsDown = true

            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertEquals(true, api.syncSettings.enabled)

            api.syncSettingsDown = false
            assertEquals(SyncOutcome.Success, alice.engine.sync())
            assertFalse(api.syncSettings.enabled)
        }

    /** A value the server will never accept is dropped for the server's own. */
    @Test
    fun `a rejected interval adopts the server's value instead of retrying`() =
        runTest {
            val alice = device("device-a")
            alice.engine.sync()
            alice.syncPrefs.setSyncIntervalMinutes(20_000)

            assertEquals(SyncOutcome.Success, alice.engine.sync())

            assertEquals(SyncSettings(enabled = true, intervalMinutes = 30), api.syncSettings)
            assertEquals(30L, alice.syncPrefs.syncIntervalMinutes.value)
            assertEquals(30L, alice.syncPrefs.lastSyncedIntervalMinutes)
        }

    private fun device(id: String): DeviceStack = DeviceStack(api, id).also { devices += it }
}
