package za.co.dielys.data.local

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a new task lands, as something that can be watched. Its own interface
 * for the same reason [DeviceIdentity] and the rest in SessionStore.kt have
 * one: [za.co.dielys.ui.tasks.TaskListViewModel] needs only this one value out
 * of [UiPrefs], and it is tested on the JVM where there is no `Context` to
 * read preferences with.
 */
interface NewTaskPlacement {
    val newItemsOnTop: StateFlow<Boolean>
}

/**
 * Whether a list's Done section is expanded — read once when the screen opens
 * rather than watched, since nothing else on the phone changes it while that
 * screen is showing. Per list (keyed on [DoneSectionPrefs.isExpanded]'s
 * `listId`), because a long list someone has tidied away and a short one they
 * are still filling are different questions.
 */
interface DoneSectionPrefs {
    fun isExpanded(listId: String): Boolean

    fun setExpanded(
        listId: String,
        expanded: Boolean,
    )
}

/**
 * Whether the half-hourly `WorkManager` floor (H3.12) runs at all, and how
 * often — the socket and push are still best-effort either way, so turning
 * this off or stretching it out trades away the guarantee that a change
 * shows up even when both of those miss, not correctness of the change
 * itself. Its own interface for the same reason [NewTaskPlacement] has one:
 * [za.co.dielys.data.sync.WorkManagerSyncScheduler] needs to read it without
 * pulling in a `Context`.
 */
interface SyncPrefs {
    val syncEnabled: StateFlow<Boolean>

    fun setSyncEnabled(value: Boolean)

    val syncIntervalMinutes: StateFlow<Long>

    fun setSyncIntervalMinutes(minutes: Long)

    /**
     * The `{enabled, intervalMinutes}` this device last confirmed with the
     * server (ADR 0010), as opposed to [syncEnabled]/[syncIntervalMinutes]
     * themselves, which are what `WorkManager` schedules from right now.
     * [za.co.dielys.data.sync.SyncEngine] compares the live values against
     * this snapshot to tell "changed here since we last agreed" (push wins)
     * from "nothing moved locally, only check what the server has" (pull
     * wins). Null means never synced — a fresh install, or an existing one
     * from before this setting synced at all — so the first run always pulls
     * rather than pushing this device's defaults over whatever another
     * device (or `web/`) already set.
     */
    val lastSyncedEnabled: Boolean?
    val lastSyncedIntervalMinutes: Long?

    fun setLastSynced(
        enabled: Boolean,
        intervalMinutes: Long,
    )

    companion object {
        /** `PeriodicWorkRequest` refuses anything shorter than this itself. */
        const val MIN_INTERVAL_MINUTES = 15L

        /** One week — the server's own bound (`MAX_SYNC_INTERVAL_MINUTES`), which
         *  answers anything longer with a 400. */
        const val MAX_INTERVAL_MINUTES = 10_080L
        const val DEFAULT_INTERVAL_MINUTES = 30L
    }
}

/**
 * The app's display language (#42), as a BCP-47 tag ("af", "zu", …) — null
 * means "follow the phone's own language", same as never having chosen one.
 * Its own interface for the same reason [NewTaskPlacement] has one: the
 * platform calls behind it aren't available to a JVM unit test.
 */
interface LocalePrefs {
    fun currentTag(): String?

    fun setTag(tag: String?)

    /**
     * Whether restarting the screen after a change is the caller's job. From
     * API 33 the system restarts the app itself, and doing it again here would
     * only make the change flicker twice on the way in.
     */
    val restartIsOurs: Boolean
}

private const val UI_PREFS_FILE = "dielys-ui-prefs"
private const val KEY_LANGUAGE_TAG = "language-tag"

/**
 * The chosen language applied to a `Context`, for the API levels below 33 that
 * have no per-app language of their own — [MainActivity] hands its base context
 * through here so every resource lookup under it resolves in that language.
 *
 * Reads preferences directly rather than through [UiPrefs] because this runs in
 * `attachBaseContext`, before Hilt has anything to inject. On 33+ it is the
 * identity: the platform has already applied the locale to the context it hands
 * us, and wrapping it again would only fight that.
 */
fun Context.withChosenLocale(): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
    val tag =
        getSharedPreferences(UI_PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, null) ?: return this
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(tag))
    return createConfigurationContext(config)
}

/**
 * Small display choices that belong to this phone, not to a list — so they
 * live in preferences rather than Room and never cross the sync boundary.
 * Two people on the same shared list can set these differently.
 */
@Singleton
class UiPrefs
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : NewTaskPlacement,
        DoneSectionPrefs,
        SyncPrefs,
        LocalePrefs {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(UI_PREFS_FILE, Context.MODE_PRIVATE)

        private val _newItemsOnTop =
            MutableStateFlow(prefs.getBoolean(KEY_NEW_ITEMS_ON_TOP, true))

        override val newItemsOnTop: StateFlow<Boolean> = _newItemsOnTop.asStateFlow()

        fun setNewItemsOnTop(value: Boolean) {
            prefs.edit().putBoolean(KEY_NEW_ITEMS_ON_TOP, value).apply()
            _newItemsOnTop.value = value
        }

        // Default expanded, matching the behaviour before this was
        // rememberable: a person who just finished something wants to see it
        // land, not go hunting for a collapsed section.
        override fun isExpanded(listId: String): Boolean =
            prefs.getBoolean(KEY_DONE_EXPANDED_PREFIX + listId, true)

        override fun setExpanded(
            listId: String,
            expanded: Boolean,
        ) {
            prefs.edit().putBoolean(KEY_DONE_EXPANDED_PREFIX + listId, expanded).apply()
        }

        private val _syncEnabled = MutableStateFlow(prefs.getBoolean(KEY_SYNC_ENABLED, true))

        override val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()

        override fun setSyncEnabled(value: Boolean) {
            prefs.edit().putBoolean(KEY_SYNC_ENABLED, value).apply()
            _syncEnabled.value = value
        }

        private val _syncIntervalMinutes =
            MutableStateFlow(
                // Clamped on read too, so a value stored before the upper
                // bound existed is healed rather than pushed and rejected.
                prefs
                    .getLong(KEY_SYNC_INTERVAL_MINUTES, SyncPrefs.DEFAULT_INTERVAL_MINUTES)
                    .coerceIn(SyncPrefs.MIN_INTERVAL_MINUTES, SyncPrefs.MAX_INTERVAL_MINUTES),
            )

        override val syncIntervalMinutes: StateFlow<Long> = _syncIntervalMinutes.asStateFlow()

        override fun setSyncIntervalMinutes(minutes: Long) {
            val clamped =
                minutes.coerceIn(SyncPrefs.MIN_INTERVAL_MINUTES, SyncPrefs.MAX_INTERVAL_MINUTES)
            prefs.edit().putLong(KEY_SYNC_INTERVAL_MINUTES, clamped).apply()
            _syncIntervalMinutes.value = clamped
        }

        override val lastSyncedEnabled: Boolean?
            get() =
                if (prefs.contains(KEY_LAST_SYNCED_ENABLED)) {
                    prefs.getBoolean(KEY_LAST_SYNCED_ENABLED, true)
                } else {
                    null
                }

        override val lastSyncedIntervalMinutes: Long?
            get() =
                if (prefs.contains(KEY_LAST_SYNCED_INTERVAL_MINUTES)) {
                    prefs.getLong(
                        KEY_LAST_SYNCED_INTERVAL_MINUTES,
                        SyncPrefs.DEFAULT_INTERVAL_MINUTES,
                    )
                } else {
                    null
                }

        override fun setLastSynced(
            enabled: Boolean,
            intervalMinutes: Long,
        ) {
            // One `edit()` for both — a torn write here would make the next
            // sync compare against a pair that never actually existed.
            prefs
                .edit()
                .putBoolean(KEY_LAST_SYNCED_ENABLED, enabled)
                .putLong(KEY_LAST_SYNCED_INTERVAL_MINUTES, intervalMinutes)
                .apply()
        }

        // Two stores, because two platforms. From API 33 the system owns this:
        // it persists the choice, applies it to the app's configuration, and
        // shows it under Settings > Apps > Language, so keeping a copy here
        // would only be a second answer to disagree with. Below that there is
        // no such setting, and the choice is ours to keep and to apply
        // (withChosenLocale, from MainActivity.attachBaseContext).
        //
        // AppCompatDelegate looks like it would cover both, but it silently
        // does nothing unless an AppCompatActivity has registered a delegate
        // with it, and this app is Compose all the way down.
        override fun currentTag(): String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                localeManager.applicationLocales.toLanguageTags().ifEmpty { null }
            } else {
                prefs.getString(KEY_LANGUAGE_TAG, null)
            }

        override fun setTag(tag: String?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                localeManager.applicationLocales =
                    tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
            } else {
                prefs.edit().putString(KEY_LANGUAGE_TAG, tag).apply()
            }
        }

        override val restartIsOurs: Boolean
            get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

        @get:RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private val localeManager: LocaleManager
            get() = context.getSystemService(LocaleManager::class.java)

        private companion object {
            const val KEY_NEW_ITEMS_ON_TOP = "new-items-on-top"
            const val KEY_DONE_EXPANDED_PREFIX = "done-expanded-"
            const val KEY_SYNC_ENABLED = "sync-enabled"
            const val KEY_SYNC_INTERVAL_MINUTES = "sync-interval-minutes"
            const val KEY_LAST_SYNCED_ENABLED = "sync-settings-last-synced-enabled"
            const val KEY_LAST_SYNCED_INTERVAL_MINUTES =
                "sync-settings-last-synced-interval-minutes"
        }
    }
