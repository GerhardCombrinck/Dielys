package za.co.dielys.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import za.co.dielys.BuildConfig

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

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Dielys", style = MaterialTheme.typography.displaySmall)
        Text(
            if (creating) {
                "Make an account. Someone can share a list with you once you have one."
            } else {
                "Sign in to the lists you share."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
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
            visualTransformation = PasswordVisualTransformation(),
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
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(end = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(if (creating) "Create account" else "Sign in")
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
