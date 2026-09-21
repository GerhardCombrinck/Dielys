package za.co.dielys.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import za.co.dielys.R
import za.co.dielys.data.AccountRepository
import za.co.dielys.data.DeleteAccountResult
import za.co.dielys.data.SessionRepository
import za.co.dielys.data.local.LocalePrefs
import za.co.dielys.data.local.StringProvider
import za.co.dielys.data.local.SyncPrefs
import za.co.dielys.data.local.UiPrefs
import javax.inject.Inject

data class SettingsUiState(
    val email: String?,
)

/** Where deleting the account has got to (ADR 0007). */
sealed interface AccountDeletion {
    data object Idle : AccountDeletion

    data object Busy : AccountDeletion

    /** Nothing changed; the screen says why and the button works again. */
    data class Failed(
        val message: String,
    ) : AccountDeletion

    /** Gone. The screen hands over to signing out, which leaves Settings behind. */
    data object Done : AccountDeletion
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val sessions: SessionRepository,
        private val accounts: AccountRepository,
        private val uiPrefs: UiPrefs,
        private val localePrefs: LocalePrefs,
        private val syncPrefs: SyncPrefs,
        private val strings: StringProvider,
    ) : ViewModel() {
        val state: SettingsUiState
            get() = SettingsUiState(email = sessions.email)

        val newItemsOnTop: StateFlow<Boolean> = uiPrefs.newItemsOnTop

        fun setNewItemsOnTop(value: Boolean) = uiPrefs.setNewItemsOnTop(value)

        val syncEnabled: StateFlow<Boolean> = syncPrefs.syncEnabled
        val syncIntervalMinutes: StateFlow<Long> = syncPrefs.syncIntervalMinutes

        /** [za.co.dielys.data.sync.WorkManagerSyncScheduler] watches both of
         *  these and re-applies them to `WorkManager` itself — this view
         *  model only writes the preference (E1.2: never reaches into
         *  `WorkManager` directly). */
        fun setSyncEnabled(value: Boolean) = syncPrefs.setSyncEnabled(value)

        fun setSyncIntervalMinutes(minutes: Long) = syncPrefs.setSyncIntervalMinutes(minutes)

        /** Null means "follow the phone's own language" — read fresh rather
         *  than watched, since choosing one recreates every Activity (the
         *  screen this value feeds is gone by the time it could change under
         *  it). */
        val languageTag: String? get() = localePrefs.currentTag()

        /** See [LocalePrefs.restartIsOurs]. */
        val restartIsOurs: Boolean get() = localePrefs.restartIsOurs

        fun setLanguage(tag: String?) = localePrefs.setTag(tag)

        private val _deletion = MutableStateFlow<AccountDeletion>(AccountDeletion.Idle)
        val deletion: StateFlow<AccountDeletion> = _deletion.asStateFlow()

        /** Asked for only after the person has confirmed — the prompt is the screen's. */
        fun deleteAccount() {
            if (_deletion.value == AccountDeletion.Busy) return
            _deletion.value = AccountDeletion.Busy
            viewModelScope.launch {
                _deletion.value =
                    when (val result = accounts.deleteAccount()) {
                        DeleteAccountResult.Deleted -> AccountDeletion.Done
                        DeleteAccountResult.Offline ->
                            AccountDeletion.Failed(strings.get(R.string.error_offline))
                        is DeleteAccountResult.ServerProblem ->
                            AccountDeletion.Failed(
                                strings.get(R.string.error_delete_account_failed, result.detail),
                            )
                    }
            }
        }
    }
