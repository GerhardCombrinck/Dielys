package za.co.dielys.data.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Where FCM delivers. Deliberately thin: it unwraps the message and hands the two
 * decisions to [PushHandler], which is where the tests are.
 *
 * There is no notification code here and there must not be. The payload is
 * data-only and carries no list content (M1), so anything shown to the user is
 * composed from Room after the sync that this triggers — which also means a
 * notification can never say something the database disagrees with.
 */
@AndroidEntryPoint
class DielysMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var handler: PushHandler

    override fun onNewToken(token: String) {
        handler.onToken(token)
    }

    /**
     * Anything that is not a wake push is ignored rather than acted on. Nothing else
     * is expected on this channel, and a message shaped differently is either a much
     * older build's or not ours at all.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        handler.onMessage(message.data)
    }
}
