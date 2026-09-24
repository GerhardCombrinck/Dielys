package za.co.dielys.data.notify

import za.co.dielys.data.local.AccountIdentity
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.ListActivityEntity
import za.co.dielys.data.local.TaskEntity
import za.co.dielys.data.remote.TaskChange
import za.co.dielys.domain.TaskFacts
import za.co.dielys.domain.classifyTaskChange
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where [za.co.dielys.data.sync.ChangeApplier] reports a task change it has just
 * applied, so a notification can be built from it later (ADR 0012). An interface
 * so the applier's many tests keep constructing it with nothing but a database.
 */
interface ChangeActivity {
    /**
     * Called inside the apply transaction, once per applied task change, with
     * the task as it was before this change landed. [quiet] is a list's first
     * pull, whose whole history is not news.
     */
    suspend fun record(
        change: TaskChange,
        before: TaskEntity?,
        quiet: Boolean,
    )

    /** Records nothing. */
    object None : ChangeActivity {
        override suspend fun record(
            change: TaskChange,
            before: TaskEntity?,
            quiet: Boolean,
        ) = Unit
    }
}

/**
 * Whether anyone is looking at the app. Set by
 * [za.co.dielys.data.sync.ForegroundWatch]; its own object because that one
 * depends on the sockets, which depend on the applier, which needs to ask this.
 */
@Singleton
class AppVisibility
    @Inject
    constructor() {
        @Volatile
        var visible: Boolean = false
    }

/**
 * Decides whether an applied change is something this account asked to hear
 * about, and if so writes the line its notification will show.
 *
 * Composed on the phone, from what the change carries and what Room already
 * has, never from the push that started the sync (M1) — which is also why this
 * runs for every change however it arrived: by catch-up, by the drain's echo,
 * or over a socket.
 *
 * Nothing is recorded:
 * - while the app is on screen — the change is already in front of them;
 * - for this account's own edits, on any device — [TaskChange.authorUserId];
 * - for a change with no author (older than the field) or before this device
 *   knows who it is signed in as — unknown is not "somebody else";
 * - on a list that is not shared, or not subscribed to that kind of change.
 */
@Singleton
class ListActivityRecorder
    @Inject
    constructor(
        private val db: DielysDatabase,
        private val account: AccountIdentity,
        private val visibility: AppVisibility,
    ) : ChangeActivity {
        override suspend fun record(
            change: TaskChange,
            before: TaskEntity?,
            quiet: Boolean,
        ) {
            if (quiet || visibility.visible) return
            val author = somebodyElse(change) ?: return
            val list = db.lists().find(change.listId)?.takeIf { it.isShared } ?: return
            val task = change.entity
            val kind =
                classifyTaskChange(
                    before?.let { TaskFacts(it.title, it.done, it.starred, it.deletedAt != null) },
                    TaskFacts(task.title, task.done, task.starred, task.deletedAt != null),
                ) ?: return
            if (kind.wire !in list.notify) return

            db.listActivity().upsert(
                ListActivityEntity(
                    listId = change.listId,
                    taskId = task.id,
                    kind = kind.wire,
                    title = task.title,
                    authorUserId = author,
                    seq = change.seq,
                ),
            )
        }

        /** The change's author, if that is known and is not this account. */
        private fun somebodyElse(change: TaskChange): String? {
            val author = change.authorUserId ?: return null
            val me = account.userId ?: return null
            return author.takeIf { it != me }
        }
    }
