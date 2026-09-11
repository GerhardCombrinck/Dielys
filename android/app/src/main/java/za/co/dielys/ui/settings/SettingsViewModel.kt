package za.co.dielys.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import za.co.dielys.data.SessionRepository
import za.co.dielys.data.local.LocalePrefs
import za.co.dielys.data.local.UiPrefs
import javax.inject.Inject

data class SettingsUiState(
    val email: String?,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val sessions: SessionRepository,
        private val uiPrefs: UiPrefs,
        private val localePrefs: LocalePrefs,
    ) : ViewModel() {
        val state: SettingsUiState
            get() = SettingsUiState(email = sessions.email)

        val newItemsOnTop: StateFlow<Boolean> = uiPrefs.newItemsOnTop

        fun setNewItemsOnTop(value: Boolean) = uiPrefs.setNewItemsOnTop(value)

        /** Null means "follow the phone's own language" — read fresh rather
         *  than watched, since choosing one recreates every Activity (the
         *  screen this value feeds is gone by the time it could change under
         *  it). */
        val languageTag: String? get() = localePrefs.currentTag()

        /** See [LocalePrefs.restartIsOurs]. */
        val restartIsOurs: Boolean get() = localePrefs.restartIsOurs

        fun setLanguage(tag: String?) = localePrefs.setTag(tag)
    }
