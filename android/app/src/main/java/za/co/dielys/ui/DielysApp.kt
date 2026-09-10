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
import za.co.dielys.ui.lists.InvitationDialog
import za.co.dielys.ui.lists.ListsScreen
import za.co.dielys.ui.lists.ListsViewModel
import za.co.dielys.ui.settings.SettingsScreen
import za.co.dielys.ui.tasks.TaskListScreen

/**
 * Three screens and an auth screen, so the whole back stack is one nullable
 * list id plus one settings flag — at most one of the two is set at a time.
 *
 * Deliberately no navigation library: a `NavHost` would earn its keep at the
 * point there are deep links or more than one way into a screen, and until then
 * it is a dependency, a set of route strings and an argument-encoding bug waiting
 * to happen, in place of the lines below.
 *
 * [ListsViewModel] is requested once, here, rather than once per screen: with no
 * navigation library, `viewModel()` resolves to the same activity-scoped instance
 * either way.
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
            onSubmit = session::submit,
        )
        return
    }

    val lists: ListsViewModel = viewModel()

    // Survive rotation and process death; cleared by signing out, because the
    // whole subtree leaves composition when there is no session.
    var openList by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = openList != null || settingsOpen) {
        openList = null
        settingsOpen = false
    }

    when {
        settingsOpen ->
            SettingsScreen(
                onBack = { settingsOpen = false },
                onSignOut = session::signOut,
            )

        openList != null -> {
            val listId = openList
            if (listId != null) {
                TaskListScreen(listId = listId, onBack = { openList = null })
            }
        }

        else ->
            ListsScreen(
                onOpen = { openList = it },
                onSettings = { settingsOpen = true },
                accountInitials = initialsFrom(session.email),
                viewModel = lists,
            )
    }

    val invitation by lists.invitation.collectAsStateWithLifecycle()
    if (invitation != null) {
        InvitationDialog(onAccept = lists::acceptInvitation, onDecline = lists::declineInvitation)
    }
}

/** "gerhard.combrinck@…" → "GC" — the initials shown on the avatar chip, since
 * there is no display name, only the email typed at sign-in. */
private fun initialsFrom(email: String?): String {
    val local = email?.substringBefore('@').orEmpty()
    val words = local.split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }
    val initials = words.take(2).map { it.first().uppercaseChar() }.joinToString("")
    return initials.ifEmpty { "?" }
}
