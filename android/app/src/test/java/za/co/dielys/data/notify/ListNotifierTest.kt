package za.co.dielys.data.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import za.co.dielys.data.DeviceStack
import za.co.dielys.data.local.ListActivityEntity
import za.co.dielys.data.local.ListEntity
import za.co.dielys.data.local.TaskEntity
import za.co.dielys.data.local.encodeNotifyEvents
import za.co.dielys.data.remote.NotifyEvent
import za.co.dielys.data.sync.FakeSyncApi

/**
 * Lines in, notification out (ADR 0012): one per list, naming who, reposted
 * only when there is something new, and never when the phone says no.
 */
@RunWith(RobolectricTestRunner::class)
class ListNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val api = FakeSyncApi()
    private lateinit var phone: DeviceStack
    private lateinit var notifier: ListNotifier

    @Before
    fun setUp() =
        runTest {
            shadowOf(
                context as Application,
            ).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            phone = DeviceStack(api, "device-b")
            notifier = ListNotifier(context, phone.db, api)
            phone.db.lists().upsert(
                ListEntity(
                    id = LIST,
                    title = "Groceries",
                    role = "member",
                    memberCount = 2,
                    notifyEvents = encodeNotifyEvents(NotifyEvent.ALL),
                ),
            )
            api.addMember(LIST, userId = "alice", email = "alice@example.com", isOwner = true)
        }

    @After
    fun close() = phone.close()

    @Test
    fun `one notification for the list, naming who did what, newest last`() =
        runTest {
            line("milk", NotifyEvent.ADDED, "Milk", seq = 2)
            line("eggs", NotifyEvent.ADDED, "Eggs", seq = 3)

            notifier.postPending()

            val posted = only()
            assertEquals("Groceries", posted.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(
                "alice added Eggs",
                posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
            )
            assertEquals(
                listOf("alice added Eggs", "alice added Milk"),
                posted.extras
                    .getCharSequenceArray(
                        Notification.EXTRA_TEXT_LINES,
                    )?.map { it.toString() },
            )
        }

    @Test
    fun `a tick reads against the task as it is now`() =
        runTest {
            phone.db.tasks().upsert(
                TaskEntity("milk", LIST, "Milk", done = false, starred = false, position = "a0"),
            )
            line("milk", NotifyEvent.CHECKED, "Milk", seq = 4)

            notifier.postPending()

            // Ticked, then unticked again before this phone heard: it says so.
            assertEquals(
                "alice unticked Milk",
                only().extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
            )
        }

    @Test
    fun `somebody no longer on the list is Someone, not a missing notification`() =
        runTest {
            line("milk", NotifyEvent.DELETED, "Milk", seq = 2, author = "gone")

            notifier.postPending()

            assertEquals(
                "Someone deleted Milk",
                only().extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
            )
        }

    @Test
    fun `nothing new since the last post is not posted again`() =
        runTest {
            line("milk", NotifyEvent.ADDED, "Milk", seq = 2)
            notifier.postPending()
            manager.cancelAll()

            notifier.postPending()

            assertEquals(0, shadowOf(manager).size())
        }

    @Test
    fun `seeing the list clears its notification and what it said`() =
        runTest {
            line("milk", NotifyEvent.ADDED, "Milk", seq = 2)
            notifier.postPending()

            notifier.forget(LIST)

            assertEquals(0, shadowOf(manager).size())
            assertEquals(emptyList<ListActivityEntity>(), phone.db.listActivity().forList(LIST))
        }

    @Test
    fun `with notifications refused, nothing is posted and nothing piles up`() =
        runTest {
            shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            line("milk", NotifyEvent.ADDED, "Milk", seq = 2)

            notifier.postPending()

            assertEquals(0, shadowOf(manager).size())
            assertEquals(emptyList<ListActivityEntity>(), phone.db.listActivity().forList(LIST))
        }

    @Test
    fun `a kind unsubscribed since it was recorded is dropped`() =
        runTest {
            line("milk", NotifyEvent.ADDED, "Milk", seq = 2)
            phone.db.lists().setNotify(LIST, encodeNotifyEvents(listOf(NotifyEvent.CHECKED)))

            notifier.postPending()

            assertEquals(0, shadowOf(manager).size())
            assertTrue(
                phone.db
                    .listActivity()
                    .forList(LIST)
                    .isEmpty(),
            )
        }

    private fun only(): Notification {
        assertEquals(1, shadowOf(manager).size())
        val posted = shadowOf(manager).getNotification(LIST, 1)
        assertTrue(posted != null)
        return checkNotNull(posted)
    }

    private suspend fun line(
        taskId: String,
        kind: String,
        title: String,
        seq: Long,
        author: String = "alice",
    ) {
        phone.db.listActivity().upsert(
            ListActivityEntity(
                listId = LIST,
                taskId = taskId,
                kind = kind,
                title = title,
                authorUserId = author,
                seq = seq,
            ),
        )
    }

    private companion object {
        const val LIST = "list-1"
    }
}
