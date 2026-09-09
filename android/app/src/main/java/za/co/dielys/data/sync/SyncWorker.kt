package za.co.dielys.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The drain, as `WorkManager` work rather than a coroutine tied to a `ViewModel`
 * scope. The distinction is the whole of H3.4: a scope dies with the process, and
 * the outbox has to be drained by something that comes back after the process is
 * killed.
 *
 * The worker itself holds no logic — it is a lifecycle wrapper around [SyncEngine]
 * so the engine stays testable on the JVM.
 */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val engine: SyncEngine,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            when (engine.sync()) {
                is SyncOutcome.Success -> Result.success()
                // Retried forever with backoff. There is no "give up" for a pending
                // mutation: the user made that edit and it has to land eventually.
                is SyncOutcome.Retry -> Result.retry()
                // Nothing to back off into — a login re-enqueues this work. Retrying
                // here would be a slow hot loop against the auth endpoint.
                is SyncOutcome.SessionExpired -> Result.failure()
            }
    }

/**
 * The one place work is enqueued, so "sync now" means the same thing whether it
 * came from a user action, a push, or app start-up.
 *
 * An interface because [za.co.dielys.data.DielysRepository] calls it after every
 * write, and the H3 tests exercise that path on the JVM. `WorkManager` needs a
 * `Context` and a background thread; the invariant being tested does not.
 */
interface SyncScheduler {
    /** Called after every local write. Asks for a drain; never waits for one. */
    fun requestSync()

    /** A floor under the push path (H3.12). */
    fun schedulePeriodicSync()
}

@Singleton
class WorkManagerSyncScheduler
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : SyncScheduler {
        /**
         * `APPEND_OR_REPLACE` keeps a single chain: a burst of ticks while offline
         * enqueues one drain, not twenty.
         */
        override fun requestSync() {
            workManager.enqueueUniqueWork(
                DRAIN_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(NETWORK)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        BACKOFF_SECONDS,
                        TimeUnit.SECONDS,
                    ).build(),
            )
        }

        /**
         * FCM is best-effort — a dropped data message must not mean a list that
         * never catches up (H3.12).
         */
        override fun schedulePeriodicSync() {
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(NETWORK)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        BACKOFF_SECONDS,
                        TimeUnit.SECONDS,
                    ).build(),
            )
        }

        private companion object {
            const val DRAIN_WORK = "dielys-sync-drain"
            const val PERIODIC_WORK = "dielys-sync-periodic"
            const val BACKOFF_SECONDS = 30L
            const val PERIOD_MINUTES = 30L
            val NETWORK: Constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        }
    }
