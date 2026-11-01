package za.co.dielys.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import za.co.dielys.data.voice.Dictated
import za.co.dielys.data.voice.VoiceAdded
import za.co.dielys.data.voice.VoiceController
import za.co.dielys.data.voice.VoiceFailure
import za.co.dielys.data.voice.VoiceState
import javax.inject.Inject

/**
 * The screen's side of the voice button (ADR 0016). Everything real lives in
 * [VoiceController]; this only turns a release into callbacks the composable
 * can use.
 */
@HiltViewModel
class VoiceViewModel
    @Inject
    constructor(
        private val controller: VoiceController,
    ) : ViewModel() {
        val available: Boolean get() = controller.available
        val state: StateFlow<VoiceState> = controller.state
        val language: StateFlow<String> = controller.language

        fun toggleLanguage() = controller.toggleLanguage()

        /** Ids of the rows voice added. */
        val dictated: StateFlow<Map<String, Dictated>> = controller.dictated

        fun correct(
            taskId: String,
            text: String,
        ) {
            viewModelScope.launch { controller.correct(taskId, text) }
        }

        fun begin(listId: String): Boolean = controller.begin(listId)

        fun cancel() = controller.cancel()

        fun finish(
            onAdded: (List<VoiceAdded>) -> Unit,
            onFailure: (VoiceFailure) -> Unit,
        ) {
            viewModelScope.launch {
                controller.finish(onFailure)?.let(onAdded)
            }
        }
    }
