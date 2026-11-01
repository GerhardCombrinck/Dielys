package za.co.dielys.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import za.co.dielys.R
import za.co.dielys.data.local.TaskEntity
import za.co.dielys.ui.TextPrompt

/** What a row needs to offer "Correct dictation" (ADR 0016). */
class DictationActions(
    val isDictated: (String) -> Boolean,
    val onCorrect: (TaskEntity) -> Unit,
)

/**
 * Handed down to the rows rather than threaded through every layer between the
 * screen and a row's menu, which already carries a long list of callbacks.
 * Null outside a list screen: no menu entry.
 */
val LocalDictation = staticCompositionLocalOf<DictationActions?> { null }

/**
 * Gives the rows inside [content] a "Correct dictation" entry for the items voice
 * added, and shows the prompt it opens. Saving renames the row and adds the
 * correction to the person's own library, so voice spells it that way next time.
 */
@Composable
fun ProvideDictation(content: @Composable () -> Unit) {
    val viewModel: VoiceViewModel = viewModel()
    val dictated by viewModel.dictated.collectAsStateWithLifecycle()
    var correcting by remember { mutableStateOf<TaskEntity?>(null) }
    val actions =
        remember(dictated) {
            DictationActions(isDictated = { it in dictated }, onCorrect = { correcting = it })
        }
    CompositionLocalProvider(LocalDictation provides actions) { content() }

    correcting?.let { task ->
        TextPrompt(
            title = stringResource(R.string.voice_correct_title, task.title),
            label = stringResource(R.string.voice_correct_label),
            // Empty, so the fix is typed straight in; the title shows what is being fixed.
            initial = "",
            onDismiss = { correcting = null },
            onConfirm = { text ->
                correcting = null
                viewModel.correct(task.id, text)
            },
        )
    }
}
