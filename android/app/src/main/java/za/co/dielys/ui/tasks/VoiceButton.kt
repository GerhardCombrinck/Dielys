package za.co.dielys.ui.tasks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import za.co.dielys.R
import za.co.dielys.data.voice.VoiceAdded
import za.co.dielys.data.voice.VoiceFailure
import za.co.dielys.data.voice.VoiceState

/** Shorter than this is a tap, not speech: a tap switches the language instead. */
private const val MIN_HOLD_MS = 350L

private val ButtonSize = 64.dp
private val ListeningSize = 76.dp
private val LISTENING_RED = Color(0xFFD32F2F)

/**
 * The voice button (ADR 0016): a circle above the add bar's send button. Hold it
 * and say what to add, in a sentence ("add coffee, bread and milk to Woolies");
 * let go and the items are added. Slide off before letting go to cancel. A quick
 * tap switches between English and Afrikaans, shown on the button. Nothing at all
 * on a phone without a microphone.
 */
@Composable
fun VoiceButton(
    listId: String,
    editing: Boolean,
    readOnly: Boolean,
) {
    val viewModel: VoiceViewModel = viewModel()
    if (!viewModel.available || editing || readOnly) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast
                    .makeText(
                        context,
                        R.string.voice_mic_denied,
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    val currentList by rememberUpdatedState(listId)
    val addedMessage = rememberAddedMessage()
    val size by animateDpAsState(if (state == VoiceState.LISTENING) ListeningSize else ButtonSize)
    val description = stringResource(R.string.cd_hold_to_talk)

    Surface(
        shape = CircleShape,
        shadowElevation = 6.dp,
        color =
            if (state ==
                VoiceState.LISTENING
            ) {
                LISTENING_RED
            } else {
                MaterialTheme.colorScheme.primary
            },
        modifier =
            Modifier
                .size(size)
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val granted =
                            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            permission.launch(Manifest.permission.RECORD_AUDIO)
                            return@awaitEachGesture
                        }
                        val listening = viewModel.begin(currentList)
                        if (listening) buzz(context)
                        var cancelled = false
                        var now = down.uptimeMillis
                        var pressed = true
                        while (pressed) {
                            val change = awaitPointerEvent().changes.first()
                            now = change.uptimeMillis
                            val box = this.size.toSize()
                            // A generous margin: a thumb drifts while talking.
                            val margin = box.width
                            if (change.position.x !in -margin..box.width + margin ||
                                change.position.y !in -margin..box.height + margin
                            ) {
                                cancelled = true
                            }
                            change.consume()
                            pressed = change.pressed
                        }
                        if (listening) buzz(context)
                        when {
                            !listening -> Unit
                            now - down.uptimeMillis < MIN_HOLD_MS -> {
                                viewModel.cancel()
                                viewModel.toggleLanguage()
                            }
                            cancelled -> viewModel.cancel()
                            else ->
                                viewModel.finish(
                                    onAdded = {
                                        Toast
                                            .makeText(
                                                context,
                                                addedMessage(it),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    },
                                    onFailure = { why ->
                                        val message =
                                            if (why == VoiceFailure.UNAVAILABLE) {
                                                R.string.voice_unavailable
                                            } else {
                                                R.string.voice_not_heard
                                            }
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    },
                                )
                        }
                    }
                },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (state == VoiceState.WORKING) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White)
                    Text(language.uppercase(), color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}

/** "Added Coffee", "Added 3 items", or "Added Milk to Woolies". */
@Composable
private fun rememberAddedMessage(): (List<VoiceAdded>) -> String {
    val context = LocalContext.current
    return { added ->
        val elsewhere = added.filter { !it.onCurrentList }.map { it.listTitle }.distinct()
        val what =
            if (added.size == 1) {
                added.first().text
            } else {
                context.resources.getQuantityString(R.plurals.voice_items, added.size, added.size)
            }
        if (elsewhere.size == 1 && added.all { !it.onCurrentList }) {
            context.getString(R.string.voice_added_to, what, elsewhere.first())
        } else {
            context.getString(R.string.voice_added, what)
        }
    }
}

/**
 * One short tick, on press and again on release, so the person knows when the
 * mic opened and closed without looking. The vibrator directly rather than view
 * haptics, which Samsung drops when system touch feedback is off.
 */
private fun buzz(context: Context) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    if (!vibrator.hasVibrator()) return
    // A firm fixed pulse rather than the system "click", which is faint on Samsung.
    val effect = VibrationEffect.createOneShot(BUZZ_MS, BUZZ_STRENGTH)
    vibrator.vibrate(effect)
}

private const val BUZZ_MS = 45L
private const val BUZZ_STRENGTH = 255
