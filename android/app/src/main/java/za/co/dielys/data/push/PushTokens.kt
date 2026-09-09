package za.co.dielys.data.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks FCM for the current token at start-up.
 *
 * `onNewToken` fires when a token is *issued*, which for an install that already
 * had one may be never. Without this, a device whose registration was lost — a
 * reinstall, a restore onto a new phone, a server-side row this account no longer
 * owns — would sit there holding a perfectly good token it had never mentioned.
 *
 * [PushHandler.onToken] drops it when it matches what is already stored, so the
 * usual launch does no work at all.
 */
@Singleton
class PushTokens
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val handler: PushHandler,
    ) {
        /**
         * Silent and harmless in a build with no `google-services.json`: without it
         * there is no default [FirebaseApp], and asking [FirebaseMessaging] for a
         * token would throw at start-up. A developer build with no Firebase project
         * still runs — it just syncs on the half-hourly floor and the socket instead
         * (H3.12).
         */
        fun refresh() {
            if (FirebaseApp.getApps(context).isEmpty()) return
            FirebaseMessaging.getInstance().token.addOnSuccessListener(handler::onToken)
        }
    }
