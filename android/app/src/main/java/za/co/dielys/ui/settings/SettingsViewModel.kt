package za.co.dielys.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import za.co.dielys.data.SessionRepository
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
    ) : ViewModel() {
        val state: SettingsUiState
            get() = SettingsUiState(email = sessions.email)

        val newItemsOnTop: StateFlow<Boolean> = uiPrefs.newItemsOnTop

        fun setNewItemsOnTop(value: Boolean) = uiPrefs.setNewItemsOnTop(value)
    }
