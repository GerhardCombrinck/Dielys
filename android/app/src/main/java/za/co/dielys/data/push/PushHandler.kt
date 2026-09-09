package za.co.dielys.data.push

import za.co.dielys.data.local.PushTokenStore
import za.co.dielys.data.sync.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What to do when FCM says something, with no `Service` around it.
 *
 * [DielysMessagingService] is a shell over this so the decisions are testable on
 * the JVM (H1): the interesting cases — a payload that is not ours, a token that
 * has not actually changed — are a map and two fields, not an emulator.
 */
@Singleton
class PushHandler
    @Inject
    constructor(
        private val scheduler: SyncScheduler,
        private val push: PushTokenStore,
    ) {
        /**
         * A new registration token. Stored, then handed to the sync run rather than
         * sent from here: `onNewToken` can fire with no network and no session, and
         * the sync engine already has the backoff and the bearer token (M2).
         *
         * A token equal to the stored one is dropped, so the server is not told
         * again on every process start. It is deliberately compared against what FCM
         * last gave us and not against what the server confirmed — those are two
         * different questions, and [za.co.dielys.data.sync.SyncEngine] asks the other
         * one.
         */
        fun onToken(token: String) {
            if (token == push.pushToken) return
            push.pushToken = token
            scheduler.requestSync()
        }

        /**
         * A wake push. The payload is a hint and never content, so the only reaction
         * is a sync; whatever ends up on screen is read from Room afterwards (M1).
         *
         * Every list is caught up, not the one named in the payload: the sync engine
         * has one entry point, and a device that has been asleep is usually behind on
         * more than the list that happened to be written last.
         *
         * Returns whether the payload was one of ours, which the caller uses only to
         * decide whether to ignore it.
         */
        fun onMessage(data: Map<String, String>): Boolean {
            if (WakeHint.from(data) == null) return false
            scheduler.requestSync()
            return true
        }
    }
