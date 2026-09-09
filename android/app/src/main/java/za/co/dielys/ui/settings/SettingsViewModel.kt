package za.co.dielys.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import za.co.dielys.BuildConfig
import za.co.dielys.data.SessionRepository
import javax.inject.Inject

data class SettingsUiState(
    val email: String?,
    val deviceId: String,
    val syncBaseUrl: String,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val sessions: SessionRepository,
    ) : ViewModel() {
        val state: SettingsUiState
            get() =
                SettingsUiState(
                    email = sessions.email,
                    deviceId = sessions.deviceId,
                    syncBaseUrl = BuildConfig.SYNC_BASE_URL,
                )
    }
