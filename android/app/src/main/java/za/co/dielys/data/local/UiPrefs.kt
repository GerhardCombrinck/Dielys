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
        }
    }
