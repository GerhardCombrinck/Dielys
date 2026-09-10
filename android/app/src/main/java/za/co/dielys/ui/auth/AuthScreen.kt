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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import za.co.dielys.BuildConfig
import za.co.dielys.R
import za.co.dielys.ui.theme.PillShape

/** The login screen's own amber, a shade off [MaterialTheme]'s secondary — the design calls
 * for the two to differ, so the sign-in button reads distinctly from a starred task. */
private val SignInAmber = Color(0xFFE2A44A)
private val BadgeNavy = Color(0xFF1B2A4A)
private val BadgeNavyLight = Color(0xFF2E4372)

/**
 * Sign in, or make an account. One screen, because they differ by a button and a
 * sentence, and a second screen would be a second place to get the password
 * field wrong.
 */
@Composable
fun AuthScreen(
    state: AuthUiState,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onMode: (AuthMode) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val creating = state.mode == AuthMode.SignUp
    var passwordVisible by remember { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()
    val visibilityIcon =
        if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
    val visibilityLabel = if (passwordVisible) "Hide password" else "Show password"

    // The keyboard takes half the screen on a phone this size, and the fields are
    // in the middle of it. The Box gives up the space the IME needs, the Column
    // scrolls inside whatever is left, and a focused field brings itself into
    // view — so the password field is reachable rather than under the keys.
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
            Text(
                if (creating) {
                    "Make an account. Someone can share a list with you once you have one."
                } else {
                    "Sign in to the lists you share."
                },
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
                        imeAction = ImeAction.Next,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = onPassword,
                label = { Text("Password") },
                singleLine = true,
                enabled = !state.busy,
                visualTransformation =
                    if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(imageVector = visibilityIcon, contentDescription = visibilityLabel)
                    }
                },
                // Only when making one: telling somebody signing in that their
                // password is too short tells an attacker their guess was too short
                // to be real.
                supportingText =
                    if (creating) {
                        { Text("At least ${state.minPasswordLength} characters.") }
                    } else {
                        null
                    },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                shape = PillShape,
                colors =
                    if (creating) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = SignInAmber,
                            contentColor = Color.White,
                        )
                    },
                // Padding outside the height: the other order shrinks the button's own
                // box to 20dp and squashes the label.
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(48.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp).padding(end = 8.dp),
                        color = if (creating) MaterialTheme.colorScheme.onSurface else Color.White,
                    )
                }
                Text(
                    when {
                        state.busy && creating -> "Creating…"
                        state.busy -> "Signing in…"
                        creating -> "Create account"
                        else -> "Sign in"
                    },
                )
            }

            TextButton(
                onClick = { onMode(if (creating) AuthMode.SignIn else AuthMode.SignUp) },
                enabled = !state.busy,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(if (creating) "I already have an account" else "Create an account")
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

            // Which server this build talks to. Debug points at dielys-dev, and
            // finding that out by watching traffic is a waste of an afternoon.
            Text(
                BuildConfig.SYNC_BASE_URL,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 32.dp),
            )
        }
    }
}
