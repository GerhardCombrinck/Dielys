package za.co.dielys.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import za.co.dielys.data.SessionRepository
import javax.inject.Inject

data class SettingsUiState(
    val email: String?,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val sessions: SessionRepository,
    ) : ViewModel() {
        val state: SettingsUiState
            get() = SettingsUiState(email = sessions.email)
    }
