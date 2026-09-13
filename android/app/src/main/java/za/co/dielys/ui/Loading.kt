package za.co.dielys.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * What a screen shows before Room has answered for the first time (#65).
 *
 * Nothing, at first. Room answers from the phone's own disk, almost always
 * inside a frame or two, and anything drawn for that long reads as a flicker —
 * the empty-list message did, and a spinner would too. So the ground is left
 * blank, which the eye takes as the screen still opening, and a spinner fades
 * in only if the wait outlives [SPINNER_DELAY_MILLIS] and has become a wait
 * worth saying something about.
 *
 * Distinct from "empty": an empty list is an answer, and it gets the empty
 * message. This is the absence of one.
 */
@Composable
fun Loading(modifier: Modifier = Modifier) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_DELAY_MILLIS)
        slow = true
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = slow, enter = fadeIn()) {
            CircularProgressIndicator()
        }
    }
}

/** Past the point a delay registers as the screen opening, and short of the
 *  point it registers as the app having stuck. */
private const val SPINNER_DELAY_MILLIS = 400L
