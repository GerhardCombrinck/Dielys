package za.co.dielys.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import za.co.dielys.R
import za.co.dielys.ui.theme.PillShape

/** The login screen's own amber, a shade off [MaterialTheme]'s secondary — the design calls
 * for the two to differ, so the sign-in button reads distinctly from a starred task. */
private val SignInAmber = Color(0xFFE2A44A)
private val DeliveredGreen = Color(0xFF7FA893)
private val BadgeNavy = Color(0xFF1B2A4A)
private val BadgeNavyLight = Color(0xFF2E4372)

/**
 * One field, one button (ADR 0005): there is no password and no separate
 * sign-up, since the server creates the account on first magic-link redeem.
 * Once a link is sent the field and button give way to a "check your email"
 * message, until the email changes again or the link is tapped.
 */
@Composable
fun AuthScreen(
    state: AuthUiState,
    onEmail: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()

    // The keyboard takes half the screen on a phone this size, and the field is
    // in the middle of it. The Box gives up the space the IME needs, the Column
    // scrolls inside whatever is left, and a focused field brings itself into
    // view — so the field is reachable rather than under the keys.
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(64.dp)
                        .background(
                            color = if (dark) BadgeNavyLight else BadgeNavy,
                            shape = RoundedCornerShape(18.dp),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }

            Text(
                "Die Lys",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                "SIT DIT OP DIE LYS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )

            if (state.linkSent) {
                Text(
                    "Check your email",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    if (state.delivered) {
                        "Delivered to ${state.email}. Open it on this device to continue."
                    } else {
                        "We sent a sign-in link to ${state.email}. It can take a few minutes " +
                            "to arrive — open it on this device to continue."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                if (state.delivered) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = DeliveredGreen)) { append("✔") }
                            append(" 📧")
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }

                // Once delivery is confirmed there is nothing a resend would fix —
                // the copy above already told them it arrived.
                if (!state.delivered) {
                    val cooldownRemaining = rememberResendCooldown(state.sentAtMillis)
                    TextButton(
                        onClick = onSubmit,
                        enabled = !state.busy && cooldownRemaining == 0,
                    ) {
                        Text(
                            if (cooldownRemaining > 0) {
                                "Resend in ${cooldownRemaining}s"
                            } else {
                                "Resend link"
                            },
                        )
                    }
                }
                TextButton(onClick = { onEmail("") }) {
                    Text("Use a different email")
                }
            } else {
                Text(
                    "Sign in with your email — we'll mail you a link, no password to " +
                        "remember.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                OutlinedTextField(
                    value = state.email,
                    onValueChange = onEmail,
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Go,
                        ),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    shape = PillShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = SignInAmber,
                            contentColor = Color.White,
                        ),
                    // Padding outside the height: the other order shrinks the button's own
                    // box to 20dp and squashes the label.
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(48.dp),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp).padding(end = 8.dp),
                            color = Color.White,
                        )
                    }
                    Text(if (state.busy) "Sending…" else "Email me a link")
                }
            }

            state.problem?.let { problem ->
                Text(
                    problem,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

private const val RESEND_COOLDOWN_MS = 60_000L

/**
 * Seconds left before a resend is allowed, ticking down to 0. Keyed on
 * [sentAtMillis] so a resend (which bumps it) restarts the cooldown rather
 * than the button going straight back to enabled.
 */
@Composable
private fun rememberResendCooldown(sentAtMillis: Long): Int {
    var remainingMs by remember(sentAtMillis) {
        mutableLongStateOf((sentAtMillis + RESEND_COOLDOWN_MS - System.currentTimeMillis()).coerceAtLeast(0))
    }
    LaunchedEffect(sentAtMillis) {
        while (remainingMs > 0) {
            delay(1_000)
            remainingMs = (sentAtMillis + RESEND_COOLDOWN_MS - System.currentTimeMillis()).coerceAtLeast(0)
        }
    }
    return (remainingMs / 1_000).toInt()
}
