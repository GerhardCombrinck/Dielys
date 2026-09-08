package za.co.dielys.data

import za.co.dielys.data.local.DeviceIdentity
import za.co.dielys.data.local.DielysDatabase
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
    val applier = ChangeApplier(db)
    val engine = SyncEngine(db, api, applier)
    val repo =
        DielysRepository(
            db = db,
            outbox = OutboxFactory(clock),
            session = FixedDevice(deviceId),
            scheduler = scheduler,
            clock = clock,
        )

    fun close() = db.close()
}

/** A device id without preferences, a `Context`, or a session behind it. */
class FixedDevice(
    override val deviceId: String,
) : DeviceIdentity

/**
 * Counts requests instead of enqueuing work. The repository must never wait for a
 * drain, so a test that ran `WorkManager` would be testing the wrong thing.
 */
class RecordingScheduler : SyncScheduler {
    var requests: Int = 0
        private set

    var periodicRequests: Int = 0
        private set

    override fun requestSync() {
        requests++
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
