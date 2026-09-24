package za.co.dielys.data.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import za.co.dielys.di.ApplicationScope
import javax.inject.Inject

/**
 * A list's notification was swiped away. What it said has been seen, so its
 * lines go, and the next change on that list starts a new notification instead
 * of bringing the dismissed ones back with it (ADR 0012).
 */
@AndroidEntryPoint
class NotificationDismissedReceiver : BroadcastReceiver() {
    @Inject
    lateinit var notifier: ListNotifier

    @Inject
    @field:ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val listId = intent.getStringExtra(ListNotifier.EXTRA_LIST_ID) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                notifier.forget(listId)
            } finally {
                pending.finish()
            }
        }
    }
}
