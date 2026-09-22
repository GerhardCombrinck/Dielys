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
import za.co.dielys.ui.help.HelpScreen
import za.co.dielys.ui.help.HelpSection
import za.co.dielys.ui.lists.InvitationDialog
import za.co.dielys.ui.lists.JoiningDialog
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
            onCode = session::onCode,
            onSubmitCode = session::submitCode,
        )
        return
    }

    val lists: ListsViewModel = viewModel()

    // Survive rotation and process death; cleared by signing out, because the
    // whole subtree leaves composition when there is no session.
    var openList by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // Help sits over whichever screen opened it, and says which card to open at.
    var helpAt by rememberSaveable { mutableStateOf<HelpSection?>(null) }
    // One level at a time. Settings can be opened from either screen now (#59),
    // so closing both at once would answer back from Settings by returning to
    // the lists rather than to the list that was open behind it.
    BackHandler(enabled = openList != null || settingsOpen || helpAt != null) {
        when {
            helpAt != null -> helpAt = null
            settingsOpen -> settingsOpen = false
            else -> openList = null
        }
    }

    when {
        helpAt != null ->
            HelpScreen(
                onBack = { helpAt = null },
                startAt = helpAt ?: HelpSection.Lists,
            )

        settingsOpen ->
            SettingsScreen(
                onBack = { settingsOpen = false },
                onSignOut = session::signOut,
                // Deleting already cleared the session and the phone's data; this
                // is only the part that takes the app back to the sign-in screen.
                onAccountDeleted = session::signOut,
            )

        openList != null -> {
            val listId = openList
            if (listId != null) {
                TaskListScreen(
                    listId = listId,
                    onBack = { openList = null },
                    onSettings = { settingsOpen = true },
                    onHelp = { helpAt = HelpSection.Lists },
                )
            }
        }

        else ->
            ListsScreen(
                onOpen = { openList = it },
                onSettings = { settingsOpen = true },
                onHelp = { helpAt = it },
                viewModel = lists,
            )
    }

    val invitation by lists.invitation.collectAsStateWithLifecycle()
    if (invitation != null) {
        InvitationDialog(onAccept = lists::acceptInvitation, onDecline = lists::declineInvitation)
    }

    // Never both at once: accepting takes the invitation, which is the same
    // moment the join starts. Over every screen rather than inside the lists
    // one, because a link can be tapped while a list is open (#61).
    val joining by lists.join.collectAsStateWithLifecycle()
    joining?.let { JoiningDialog(state = it, onDismiss = lists::dismissJoin) }
}
