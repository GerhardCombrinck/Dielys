package za.co.dielys

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Asks Play whether a newer build exists, instead of waiting for Play to get
 * round to it (#88). Play's own auto-update is opportunistic — the device
 * checks in roughly daily and installs when it is charging, idle and on wifi —
 * which is fine for a stable app and useless for a tester who wants the build
 * that was tagged ten minutes ago.
 *
 * **Flexible, not immediate.** The download runs in the background and the app
 * stays usable throughout; the user is asked to restart only once it is ready.
 * An immediate update is a fullscreen wall, and nothing this app ships is
 * urgent enough to justify one — the old build keeps working. If a release
 * ever *is* that urgent (a protocol change that makes old clients wrong), that
 * is the point to read `updatePriority()`, which `release-android.yml` can set
 * per release via the upload action's `inAppUpdatePriority` input.
 *
 * Nothing here needs a Play Store to be present: off Play — a debug build, a
 * sideloaded APK, a device without Play Services — the task fails or reports
 * no update, [restartReady] stays false, and no prompt is ever shown.
 *
 * Lives beside [MainActivity] rather than under `ui/` because it is neither
 * Compose nor Room: it holds an Activity, which E1 forbids a `ViewModel` from
 * doing, so the Activity owns it directly.
 */
class AppUpdates(
    private val activity: ComponentActivity,
) : DefaultLifecycleObserver {
    private val manager = AppUpdateManagerFactory.create(activity)

    /**
     * True once a downloaded update is sitting there waiting to be installed.
     * Compose state rather than a `Flow` because it has exactly one reader —
     * the prompt in [MainActivity] — and no ViewModel in between.
     */
    var restartReady by mutableStateOf(false)
        private set

    /**
     * Registered here, at construction, because `registerForActivityResult`
     * has to happen before the Activity is STARTED or it throws.
     *
     * The result is logged and otherwise ignored. A declined or cancelled
     * update is a choice, not a failure: the next `onResume` offers it again,
     * and nagging harder than that is what [AppUpdateType.IMMEDIATE] is for.
     */
    private val launcher =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result -> Log.d(TAG, "update flow closed with ${result.resultCode}") }

    /**
     * Tracks the status both ways, not just into DOWNLOADED: Play can drop a
     * finished download (a failed or cancelled install, or its own cleanup),
     * and a bar still offering a restart after that is a button that does
     * nothing.
     */
    private val installListener =
        InstallStateUpdatedListener { state ->
            restartReady = state.installStatus() == InstallStatus.DOWNLOADED
        }

    init {
        manager.registerListener(installListener)
        activity.lifecycle.addObserver(this)
    }

    /**
     * Checked on every resume, not just on launch. Two reasons: a flexible
     * download can finish while the app is backgrounded, which is the common
     * case for a download of any size; and an update published while the app
     * was open would otherwise go unnoticed until the process died.
     *
     * Debounced process-wide, because "every resume" is more often than it
     * sounds. Applying the chosen language (#42) recreates the Activity on
     * every cold start, so a launch built two of these and asked Play twice;
     * and an app flicked in and out of the foreground would ask on each pass.
     * Play publishes on the order of days, so [CHECK_INTERVAL_MS] loses
     * nothing and the resume-after-process-death case still checks, since a
     * new process starts the clock at zero.
     */
    override fun onResume(owner: LifecycleOwner) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckedAt < CHECK_INTERVAL_MS) return
        lastCheckedAt = now

        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                // Already downloaded and waiting — the listener above only
                // fires for a download that happens while we are listening,
                // so a resume after process death has to find it this way.
                // Assigned either way, so a download Play has since dropped
                // takes the bar down with it.
                restartReady = info.installStatus() == InstallStatus.DOWNLOADED
                if (restartReady) return@addOnSuccessListener
                val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                if (available && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                    startFlexibleUpdate(info)
                }
            }.addOnFailureListener {
                // Every non-Play install lands here. Not a user-facing problem:
                // it means no in-app update, not a broken app.
                Log.d(TAG, "no update check available: ${it.message}")
            }
    }

    private fun startFlexibleUpdate(info: AppUpdateInfo) {
        runCatching {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
            )
        }.onFailure { Log.d(TAG, "could not start update flow: ${it.message}") }
    }

    /**
     * Installs the downloaded update and restarts the app.
     *
     * On success the process is killed and nothing after this runs. A failure
     * means Play no longer has the download, even though it once reported one
     * (seen on a device as `error.code=-7`, "Download not present"). So drop
     * the bar and ask Play again straight away, skipping the debounce, which
     * offers the download afresh instead of leaving a Restart that can't work.
     */
    fun completeUpdate() {
        manager.completeUpdate().addOnFailureListener {
            Log.d(TAG, "could not complete update: ${it.message}")
            restartReady = false
            lastCheckedAt = 0L
            onResume(activity)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        manager.unregisterListener(installListener)
    }

    private companion object {
        const val TAG = "AppUpdates"

        /** Long enough to collapse a recreation and ordinary app-switching. */
        const val CHECK_INTERVAL_MS = 15 * 60 * 1000L

        /**
         * Process-wide, not per-instance: the duplicate this exists to stop
         * comes from *two* [AppUpdates], one per Activity instance, so an
         * instance field would not see the other's check.
         *
         * Elapsed realtime, so it cannot be skewed by a clock change.
         */
        @Volatile
        var lastCheckedAt = 0L
    }
}
