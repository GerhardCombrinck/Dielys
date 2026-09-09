package za.co.dielys.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import za.co.dielys.ui.auth.AuthScreen
import za.co.dielys.ui.auth.SessionViewModel
import za.co.dielys.ui.lists.ListsScreen
import za.co.dielys.ui.tasks.TaskListScreen

/**
 * Two screens and an auth screen, so the whole back stack is one nullable list id.
 *
 * Deliberately no navigation library: a `NavHost` would earn its keep at the
 * point there are deep links or more than one way into a screen, and until then
 * it is a dependency, a set of route strings and an argument-encoding bug waiting
 * to happen, in place of the line below.
 */
@Composable
fun DielysApp() {
    val session: SessionViewModel = viewModel()
    val signedIn by session.signedIn.collectAsStateWithLifecycle()

    if (!signedIn) {
        val form by session.form.collectAsStateWithLifecycle()
        AuthScreen(
            state = form,
            onEmail = session::onEmail,
            onPassword = session::onPassword,
            onMode = session::onMode,
            onSubmit = session::submit,
        )
        return
    }

    // Survives rotation and process death; cleared by signing out, because the
    // whole subtree leaves composition when there is no session.
    var openList by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = openList != null) { openList = null }

    when (val listId = openList) {
        null ->
            ListsScreen(
                onOpen = { openList = it },
                onSignOut = session::signOut,
            )

        else ->
            TaskListScreen(
                listId = listId,
                onBack = { openList = null },
            )
    }
}
