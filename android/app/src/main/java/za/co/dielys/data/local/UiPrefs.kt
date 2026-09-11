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
 * Small display choices that belong to this phone, not to a list — so they
 * live in preferences rather than Room and never cross the sync boundary.
 * Two people on the same shared list can set these differently.
 */
@Singleton
class UiPrefs
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : NewTaskPlacement {
        private val prefs: SharedPreferences =
            context.getSharedPreferences("dielys-ui-prefs", Context.MODE_PRIVATE)

        private val _newItemsOnTop =
            MutableStateFlow(prefs.getBoolean(KEY_NEW_ITEMS_ON_TOP, true))

        override val newItemsOnTop: StateFlow<Boolean> = _newItemsOnTop.asStateFlow()

        fun setNewItemsOnTop(value: Boolean) {
            prefs.edit().putBoolean(KEY_NEW_ITEMS_ON_TOP, value).apply()
            _newItemsOnTop.value = value
        }

        private companion object {
            const val KEY_NEW_ITEMS_ON_TOP = "new-items-on-top"
        }
    }
