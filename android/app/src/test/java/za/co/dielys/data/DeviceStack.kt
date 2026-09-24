package za.co.dielys.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import za.co.dielys.data.local.AccountIdentity
import za.co.dielys.data.local.CatchUpSweeps
import za.co.dielys.data.local.DeviceIdentity
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.FirstSync
import za.co.dielys.data.local.PushTokenStore
import za.co.dielys.data.local.SyncPrefs
import za.co.dielys.data.notify.AppVisibility
import za.co.dielys.data.notify.ListActivityRecorder
import za.co.dielys.data.sync.ChangeApplier
import za.co.dielys.data.sync.FakeSyncApi
import za.co.dielys.data.sync.OutboxFactory
import za.co.dielys.data.sync.SyncEngine
import za.co.dielys.data.sync.SyncScheduler
import za.co.dielys.domain.Clock

/**
 * One phone: its own database, its own outbox, its own cursor, pointed at a shared
 * [FakeSyncApi]. Two of these against one fake is a two-device household, which is
 * exactly what the H3 scenarios are about.
 */
class DeviceStack(
    api: FakeSyncApi,
    deviceId: String,
) {
    val db: DielysDatabase = inMemoryDatabase()
    val clock = SteppingClock()
    val scheduler = RecordingScheduler()
    val account = FixedAccount(userId = "$deviceId-user", email = "$deviceId@dielys.test")

    /** Whether the app is on screen. Off, as for a phone in a pocket. */
    val visibility = AppVisibility()
    val activity = ListActivityRecorder(db, account, visibility)
    val applier = ChangeApplier(db, activity)
    val push = FakePushTokens()
    val sweeps = FakeSweeps()
    val syncPrefs = FakeSyncPrefs()
    val engine = SyncEngine(db, api, applier, push, sweeps, clock, syncPrefs)
    val sharing = SharingRepository(api, scheduler)
    val invites = PendingInvite()

    // Not started: the colour a list wears is not part of any sync scenario, so
    // nothing here needs the collector running. The repository still asks it
    // which colour a new list gets, and that is a plain query.
    val accents = ListAccents(db, CoroutineScope(Dispatchers.Unconfined))
    val repo =
        DielysRepository(
            db = db,
            accents = accents,
            outbox = OutboxFactory(clock),
            session = FixedDevice(deviceId),
            scheduler = scheduler,
            clock = clock,
        )

    fun close() = db.close()
}

/**
 * The two push values in memory. Preferences need a `Context`; the sync engine only
 * ever reads one and writes the other (M2).
 */
class FakePushTokens(
    override var pushToken: String? = null,
    override var pushTokenSent: String? = null,
) : PushTokenStore

/**
 * In-memory [SyncPrefs] — a JVM test needs neither the `Context` real
 * preferences would, nor `WorkManager` to observe them.
 *
 * [lastSyncedEnabled]/[lastSyncedIntervalMinutes] start null, the same "never
 * synced" state a fresh install has, so a test's first [SyncEngine.sync] call
 * pulls rather than pushing [enabled]/[intervalMinutes]'s constructor
 * defaults over whatever the fake server already holds.
 */
class FakeSyncPrefs(
    enabled: Boolean = true,
    intervalMinutes: Long = SyncPrefs.DEFAULT_INTERVAL_MINUTES,
) : SyncPrefs {
    private val _syncEnabled = MutableStateFlow(enabled)
    override val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()

    override fun setSyncEnabled(value: Boolean) {
        _syncEnabled.value = value
    }

    private val _syncIntervalMinutes = MutableStateFlow(intervalMinutes)
    override val syncIntervalMinutes: StateFlow<Long> = _syncIntervalMinutes.asStateFlow()

    override fun setSyncIntervalMinutes(minutes: Long) {
        _syncIntervalMinutes.value = minutes
    }

    override var lastSyncedEnabled: Boolean? = null
        private set

    override var lastSyncedIntervalMinutes: Long? = null
        private set

    override fun setLastSynced(
        enabled: Boolean,
        intervalMinutes: Long,
    ) {
        lastSyncedEnabled = enabled
        lastSyncedIntervalMinutes = intervalMinutes
    }
}

/** When the last full catch-up ran, in memory, and whether one ever has (#66). */
class FakeSweeps(
    lastFullCatchUpAt: Long? = null,
) : CatchUpSweeps,
    FirstSync {
    private val pulled = MutableStateFlow(lastFullCatchUpAt != null)

    override val listsPulled: StateFlow<Boolean> = pulled.asStateFlow()

    override var lastFullCatchUpAt: Long? = lastFullCatchUpAt
        set(value) {
            field = value
            pulled.value = value != null
        }
}

/** A device id without preferences, a `Context`, or a session behind it. */
class FixedDevice(
    override val deviceId: String,
) : DeviceIdentity

/** Who a test is signed in as, for the screens that have to recognise
 *  themselves among the people on a shared list (#60). */
class FixedAccount(
    /** A `var` so a test can drop it: a session older than the server's own
     *  login answer has no stored id, and the sheet still has to recognise
     *  itself (#60). */
    override var userId: String? = null,
    override var email: String? = null,
) : AccountIdentity

/**
 * Counts requests instead of enqueuing work. The repository must never wait for a
 * drain, so a test that ran `WorkManager` would be testing the wrong thing.
 */
class RecordingScheduler : SyncScheduler {
    var requests: Int = 0
        private set

    var periodicRequests: Int = 0
        private set

    /** How many of [requests] came from a wake push, asking to run now (ADR 0012). */
    var urgentRequests: Int = 0
        private set

    override fun requestSync() {
        requests++
    }

    override fun requestUrgentSync() {
        urgentRequests++
        requestSync()
    }

    override fun schedulePeriodicSync() {
        periodicRequests++
    }
}

/**
 * Advances a millisecond per read, so ids minted in sequence sort in the order they
 * were created. Nothing under test trusts this clock for ordering (F5.9) — it only
 * has to be distinct.
 */
class SteppingClock(
    private var millis: Long = 1_760_000_000_000L,
) : Clock {
    override fun nowMillis(): Long = millis++
}
