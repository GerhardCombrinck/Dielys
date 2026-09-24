package za.co.dielys.data.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import za.co.dielys.MainActivity
import za.co.dielys.R
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.ListActivityEntity
import za.co.dielys.data.local.ListEntity
import za.co.dielys.data.local.TaskEntity
import za.co.dielys.data.local.withChosenLocale
import za.co.dielys.data.remote.ApiException
import za.co.dielys.data.remote.NotifyEvent
import za.co.dielys.data.remote.SyncApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a screen may do about a list's notification: say it has been seen. An
 * interface so a view model can reach it without reaching a `Context` (E1).
 */
interface ListNotifications {
    /** The list is on screen: its notification and the lines behind it go. */
    suspend fun forget(listId: String)
}

/**
 * Turns the lines [ListActivityRecorder] wrote into one notification per list
 * (ADR 0012) — composed here, from Room, after the sync; never from a push (M1).
 *
 * One notification per list, updated in place: its tag is the list id, so the
 * third change on "Groceries" rewrites that list's notification rather than
 * stacking a third one under it. A list is only reposted when it has a line
 * nobody has seen yet, so a sync that brought nothing new does not buzz again.
 */
@Singleton
class ListNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: DielysDatabase,
        private val api: SyncApi,
    ) : ListNotifications {
        /** Who a user id is, as shown in a line. In memory only: the list's
         * members are asked again after a process start, which is cheap and never
         * stale for longer than that. */
        private val names = ConcurrentHashMap<String, String>()

        /** Posts every list with news. Called at the end of each sync run. */
        suspend fun postPending() {
            val manager = NotificationManagerCompat.from(context)
            for (listId in db.listActivity().listsWithNews()) postList(manager, listId)
        }

        private suspend fun postList(
            manager: NotificationManagerCompat,
            listId: String,
        ) {
            val list = db.lists().find(listId)?.takeIf { it.isShared && it.deletedAt == null }
            val lines = list?.let { subscribedLines(it) }.orEmpty()
            when {
                // Unsubscribed, unshared or gone since it was recorded.
                list == null || lines.isEmpty() -> forget(listId)
                // Nothing recorded now could ever be shown; keeping it would only
                // make the first notification after permission is granted a wall
                // of old news.
                !allowed(manager) -> db.listActivity().clear(listId)
                else -> {
                    post(manager, list, lines)
                    db.listActivity().markPosted(listId)
                }
            }
        }

        /** What is still recorded for [list], in the kinds it is still subscribed to. */
        private suspend fun subscribedLines(list: ListEntity): List<ListActivityEntity> =
            db.listActivity().forList(list.id).filter { it.kind in list.notify }

        /**
         * The list was opened, or its notification swiped away: what it said has
         * been seen, so it goes, and the next change starts a fresh one.
         */
        override suspend fun forget(listId: String) {
            db.listActivity().clear(listId)
            NotificationManagerCompat.from(context).cancel(listId, NOTIFICATION_ID)
        }

        private suspend fun post(
            manager: NotificationManagerCompat,
            list: ListEntity,
            rows: List<ListActivityEntity>,
        ) {
            ensureChannel(manager)
            val strings = context.withChosenLocale()
            learnNames(list.id, rows.map { it.authorUserId })
            val someone = strings.getString(R.string.notify_someone)
            val lines = rows.map { line(strings, it, names[it.authorUserId] ?: someone) }
            val title = list.title.ifBlank { strings.getString(R.string.untitled_list) }
            val latest = lines.last()

            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(latest)
                    .setNumber(lines.size)
                    .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                    .setAutoCancel(true)
                    .setContentIntent(openList(list.id))
                    .setDeleteIntent(dismissed(list.id))
            if (lines.size > 1) {
                val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
                // Newest first, as a glance wants them.
                lines.asReversed().take(MAX_LINES).forEach(style::addLine)
                if (lines.size > MAX_LINES) {
                    style.setSummaryText(
                        strings.getString(
                            R.string.notify_more,
                            lines.size - MAX_LINES,
                        ),
                    )
                }
                builder.setStyle(style)
            }
            if (permitted()) manager.notify(list.id, NOTIFICATION_ID, builder.build())
        }

        /**
         * One line. Read against the task as it is *now*, where there still is
         * one: a task ticked and unticked again says "unticked", and a renamed
         * one says its current name.
         */
        private suspend fun line(
            strings: Context,
            row: ListActivityEntity,
            who: String,
        ): String {
            val task = db.tasks().find(row.taskId)
            val title = task?.title ?: row.title
            val text =
                when (row.kind) {
                    NotifyEvent.ADDED -> R.string.notify_line_added
                    NotifyEvent.CHECKED -> checkedLine(task)
                    NotifyEvent.DELETED -> R.string.notify_line_deleted
                    else -> R.string.notify_line_updated
                }
            return strings.getString(text, who, title)
        }

        /**
         * Names people by the part of their email before the `@` — the only name
         * this system has for anybody (`ListMember`), shortened to fit a line.
         * The list's members are asked for once, and only when somebody in
         * [authors] is not known yet. If that fails, or they have since left, the
         * line says "Someone" rather than the notification not appearing at all.
         */
        private suspend fun learnNames(
            listId: String,
            authors: List<String>,
        ) {
            if (authors.all(names::containsKey)) return
            try {
                for (member in api.listMembers(listId)) {
                    names[member.userId] = member.email.substringBefore('@')
                }
            } catch (_: ApiException) {
                // Offline between the sync and here, or no longer on the list.
            }
        }

        private fun openList(listId: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                listId.hashCode(),
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION_OPEN_LIST)
                    .putExtra(EXTRA_LIST_ID, listId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun dismissed(listId: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                listId.hashCode(),
                Intent(
                    context,
                    NotificationDismissedReceiver::class.java,
                ).putExtra(EXTRA_LIST_ID, listId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun ensureChannel(manager: NotificationManagerCompat) {
            val strings = context.withChosenLocale()
            manager.createNotificationChannel(
                NotificationChannelCompat
                    .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(strings.getString(R.string.notify_channel_name))
                    .setDescription(strings.getString(R.string.notify_channel_description))
                    .build(),
            )
        }

        private fun allowed(manager: NotificationManagerCompat): Boolean =
            permitted() && manager.areNotificationsEnabled()

        /** Android 13 made posting a runtime permission, asked for when somebody
         * first turns a list's notifications on (TaskListScreen). */
        private fun permitted(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) ==
                PackageManager.PERMISSION_GRANTED

        companion object {
            const val CHANNEL_ID = "list-activity"
            const val ACTION_OPEN_LIST = "za.co.dielys.OPEN_LIST"
            const val EXTRA_LIST_ID = "listId"

            /** The tag carries the list; the id is the same for all of them. */
            private const val NOTIFICATION_ID = 1

            /** InboxStyle shows about this many before the system truncates. */
            private const val MAX_LINES = 6
        }
    }

/** Ticked off, or — if it is not done now — unticked again. */
private fun checkedLine(task: TaskEntity?): Int =
    if (task?.done == false) R.string.notify_line_unchecked else R.string.notify_line_checked
