package za.co.dielys.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.SessionSignal
import za.co.dielys.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** First retry delay, and the ceiling it doubles towards. */
private const val BACKOFF_FLOOR_MILLIS = 1_000L
private const val BACKOFF_CEILING_MILLIS = 60_000L

/**
 * How long the app has to be out of sight before its sockets are closed.
 *
 * A rotation takes the only Activity down and puts it straight back up, so
 * without this every rotation would close and reopen a socket per list. Short
 * enough that a phone put in a pocket stops holding connections almost at once.
 */
private const val BACKGROUND_GRACE_MILLIS = 2_000L

/**
 * Keeps one socket open per list, while the app is in front of somebody and there
 * is a session to open it with.
 *
 * Only in the foreground, deliberately. A backgrounded phone is woken by an FCM
 * data message instead (M2), which is what lets the process be frozen in Doze
 * rather than holding connections open; the half-hourly `WorkManager` sync is the
 * floor under both (H3.12). Holding a socket while backgrounded would fight the
 * platform for no gain — nobody is looking at the screen it would update.
 *
 * Nothing here is required for correctness. Every change this delivers would
 * arrive anyway on the next catch-up pull; all it removes is the wait.
 */
@Singleton
class SyncSockets
    @Inject
    constructor(
        private val session: ListSocketSession,
        private val db: DielysDatabase,
        private val signal: SessionSignal,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val foreground = MutableStateFlow(false)
        private var supervisor: Job? = null

        /** Called once, from `Application.onCreate`. */
        fun start() {
            if (supervisor != null) return
            supervisor = scope.launch { supervise() }
        }

        fun onForeground() {
            foreground.value = true
        }

        fun onBackground() {
            foreground.value = false
        }

        /**
         * The set of lists that should have a socket right now, and nothing else.
         *
         * Reading the list ids from Room rather than from a call is what makes a
         * list joined by invite connect on its own: `discoverLists` writes the row,
         * the query emits, and a socket opens. Signing out empties the set the same
         * way, because the session flow goes false before anything else notices.
         */
        private suspend fun supervise() {
            val wanted =
                combine(
                    settledForeground(),
                    signal.signedIn,
                    db.lists().observeKnownIds(),
                ) { visible, signedIn, ids ->
                    if (visible && signedIn) ids.toSet() else emptySet()
                }.distinctUntilChanged()

            coroutineScope {
                val open = mutableMapOf<String, Job>()
                wanted.collect { ids ->
                    for (gone in open.keys - ids) open.remove(gone)?.cancel()
                    for (added in ids - open.keys) open[added] = launch { connect(added) }
                }
            }
        }

        /**
         * One list, reconnected for as long as it is wanted.
         *
         * The backoff resets only after a session that got as far as `hello-ok`.
         * A server that accepts the upgrade and then refuses the handshake would
         * otherwise be retried at the floor delay forever.
         */
        private suspend fun connect(listId: String) {
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                when (val end = session.run(listId)) {
                    is SessionEnd.Transient -> if (end.handshaked) attempt = 0
                    // Both leave the HTTP path alone, which is the one that has to
                    // work. Another foreground or another list makes a fresh
                    // attempt; nothing else should.
                    is SessionEnd.Permanent -> return
                    SessionEnd.SessionGone -> return
                }
                delay(backoff(attempt))
                attempt += 1
            }
        }

        /**
         * Exponential with half jitter. Two phones in one household is not a
         * thundering herd, but a server that has just come back should not be met
         * by every client it dropped at the same instant, and the jitter costs a
         * line.
         */
        private fun backoff(attempt: Int): Long {
            val capped =
                (BACKOFF_FLOOR_MILLIS shl attempt.coerceIn(0, MAX_SHIFT))
                    .coerceAtMost(BACKOFF_CEILING_MILLIS)
            return capped / 2 + Random.nextLong(capped / 2 + 1)
        }

        /** [foreground], with a going-dark transition held back through a rotation. */
        private fun settledForeground(): Flow<Boolean> =
            channelFlow {
                var pending: Job? = null
                foreground.collect { visible ->
                    pending?.cancel()
                    pending = null
                    if (visible) {
                        send(true)
                    } else {
                        pending =
                            launch {
                                delay(BACKGROUND_GRACE_MILLIS)
                                send(false)
                            }
                    }
                }
            }.distinctUntilChanged()

        private companion object {
            /** `1_000L shl 6` is already past the ceiling; further shifting is noise. */
            const val MAX_SHIFT = 6
        }
    }
