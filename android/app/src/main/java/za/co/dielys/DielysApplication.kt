package za.co.dielys

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import za.co.dielys.data.sync.SyncScheduler
import javax.inject.Inject

/**
 * `WorkManager` is initialised here rather than by its default `androidx.startup`
 * provider (removed in the manifest) so the drain worker can be constructed by
 * Hilt and reach the sync engine.
 */
@HiltAndroidApp
class DielysApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var scheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // A floor under the push path: a dropped FCM message must not leave a list
        // permanently behind (H3.12).
        scheduler.schedulePeriodicSync()
    }
}
