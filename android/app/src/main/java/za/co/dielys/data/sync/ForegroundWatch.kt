package za.co.dielys.data.sync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether any Activity is on screen, counted.
 *
 * `androidx.lifecycle:lifecycle-process` answers the same question in three
 * lines, and this is what it is instead: a dependency buys nothing here that
 * seven empty overrides do not already do (N1). Its one real feature — a grace
 * period so a rotation does not read as the app leaving — lives in [SyncSockets]
 * where the decision it affects is made, rather than in a library that has to
 * guess a duration for everybody.
 */
@Singleton
class ForegroundWatch
    @Inject
    constructor(
        private val sockets: SyncSockets,
    ) : Application.ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started += 1
            if (started == 1) sockets.onForeground()
        }

        override fun onActivityStopped(activity: Activity) {
            started -= 1
            if (started == 0) sockets.onBackground()
        }

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
