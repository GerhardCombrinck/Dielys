package za.co.dielys

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import za.co.dielys.data.push.PushTokens
import za.co.dielys.data.sync.ForegroundWatch
import za.co.dielys.data.sync.SyncScheduler
import za.co.dielys.data.sync.SyncSockets
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

    @Inject
    lateinit var pushTokens: PushTokens

    @Inject
    lateinit var sockets: SyncSockets

    @Inject
    lateinit var foreground: ForegroundWatch

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // A floor under the push path: a dropped FCM message must not leave a list
        // permanently behind (H3.12).
        scheduler.schedulePeriodicSync()
        // And once now. Opening the app is the moment somebody is looking at it,
        // which is the worst moment to be up to half an hour out of date. Costs
        // nothing when there is no session: the drain fails on the first 401.
        scheduler.requestSync()
        // FCM only calls onNewToken when it issues one, which for a long-lived
        // install is never. This is how a registration the server lost gets sent
        // again (M2).
        pushTokens.refresh()
        // The socket is the latency path and nothing else — it opens only while
        // somebody is looking at the app, and closes when they stop. Everything
        // above still works with it never connecting at all.
        registerActivityLifecycleCallbacks(foreground)
        sockets.start()
    }
}
