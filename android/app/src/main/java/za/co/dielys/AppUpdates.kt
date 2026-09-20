package za.co.dielys

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

    private val installListener =
        InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) restartReady = true
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
     */
    override fun onResume(owner: LifecycleOwner) {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                // Already downloaded and waiting — the listener above only
                // fires for a download that happens while we are listening,
                // so a resume after process death has to find it this way.
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    restartReady = true
                    return@addOnSuccessListener
                }
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

    /** Installs the downloaded update and restarts the app. */
    fun completeUpdate() {
        manager.completeUpdate()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        manager.unregisterListener(installListener)
    }

    private companion object {
        const val TAG = "AppUpdates"
    }
}
