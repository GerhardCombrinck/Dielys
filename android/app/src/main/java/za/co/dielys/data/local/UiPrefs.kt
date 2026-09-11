package za.co.dielys.data.local

import android.content.Context
import android.content.SharedPreferences
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
 * Small display choices that belong to this phone, not to a list — so they
 * live in preferences rather than Room and never cross the sync boundary.
 * Two people on the same shared list can set these differently.
 */
@Singleton
class UiPrefs
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : NewTaskPlacement,
        DoneSectionPrefs {
        private val prefs: SharedPreferences =
            context.getSharedPreferences("dielys-ui-prefs", Context.MODE_PRIVATE)

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

        private companion object {
            const val KEY_NEW_ITEMS_ON_TOP = "new-items-on-top"
            const val KEY_DONE_EXPANDED_PREFIX = "done-expanded-"
        }
    }
