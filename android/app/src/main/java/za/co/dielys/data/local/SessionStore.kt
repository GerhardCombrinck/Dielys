package za.co.dielys.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import za.co.dielys.domain.Uuid7
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device id on its own.
 *
 * Callers that need only the identifier — the repository, when stamping a
 * mutation — depend on this rather than on session storage, so they carry no
 * dependency on tokens, preferences or a `Context`.
 */
interface DeviceIdentity {
    val deviceId: String
}

/**
 * Who this device is signed in as, for the one screen that has to point at a
 * row and say "that one is you" — the "shared with" sheet (#60).
 *
 * Its own face of the same store rather than a look at all of it, for the same
 * reason [DeviceIdentity] is: a caller that needs to recognise itself in a list
 * of people has no business with tokens.
 *
 * Both null before anybody has signed in. [userId] is null on a session that
 * predates the server sending one back, which is why the sheet matches on
 * [email] and uses the id only to act.
 */
interface AccountIdentity {
    val userId: String?

    val email: String?
}

/**
 * The FCM registration token, and whether the server has been told about it.
 *
 * Separate from the session for the same reason [DeviceIdentity] is: the sync
 * engine needs these two values and nothing else about a session, and it is
 * tested on the JVM where there is no `Context` to read preferences with.
 */
interface PushTokenStore {
    /** What FCM last issued this install, or null before it has issued anything. */
    var pushToken: String?

    /**
     * The value the server has confirmed. Cleared on sign-out, because the next
     * account to sign in on this phone has to claim the device row for itself
     * (M2) — otherwise it would be woken for somebody else's lists.
     */
    var pushTokenSent: String?
}

/**
 * Whether there is a session, as something that can be watched.
 *
 * The socket supervisor has to react to a sign-in and a sign-out, and it cannot
 * be told: it would have to be injected into [za.co.dielys.data.SessionRepository]
 * to be told, and that repository is already what hands out the access token the
 * supervisor uses — a cycle Dagger would refuse. Watching the one piece of state
 * both of them care about breaks it, and leaves the supervisor a reaction to
 * state rather than a thing with an on switch somebody has to remember to flip.
 */
interface SessionSignal {
    /** True while a refresh token exists. Access tokens expire; sessions do not. */
    val signedIn: StateFlow<Boolean>
}

/**
 * Device identity and the current session, in preferences rather than the database
 * — they must survive a schema problem that the database might not, and none of it
 * is queried or joined.
 *
 * The device id is minted once, on first read, and never changes. It is the same
 * identifier the server uses to break F5.4 conflict ties, and the same one sent on
 * login: introduced once, used for both (L1).
 */
@Singleton
class SessionStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : DeviceIdentity,
        AccountIdentity,
        PushTokenStore,
        SessionSignal {
        private val prefs: SharedPreferences =
            context.getSharedPreferences("dielys-session", Context.MODE_PRIVATE)

        private val session = MutableStateFlow(prefs.getString(KEY_REFRESH_TOKEN, null) != null)

        override val signedIn: StateFlow<Boolean> = session.asStateFlow()

        @get:Synchronized
        override val deviceId: String
            get() =
                prefs.getString(KEY_DEVICE_ID, null) ?: Uuid7.generate().also {
                    prefs.edit().putString(KEY_DEVICE_ID, it).apply()
                }

        var accessToken: String?
            get() = prefs.getString(KEY_ACCESS_TOKEN, null)
            set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

        /**
         * The one write that decides whether there is a session, so it is also the
         * one that moves [signedIn]. Setting it anywhere else would let the two
         * disagree.
         */
        var refreshToken: String?
            get() = prefs.getString(KEY_REFRESH_TOKEN, null)
            set(value) {
                prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()
                session.value = value != null
            }

        override var userId: String?
            get() = prefs.getString(KEY_USER_ID, null)
            set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

        /**
         * Not sent by the server on login or refresh — kept from the sign-in
         * form so Settings has something to show.
         */
        override var email: String?
            get() = prefs.getString(KEY_EMAIL, null)
            set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

        override var pushToken: String?
            get() = prefs.getString(KEY_PUSH_TOKEN, null)
            set(value) = prefs.edit().putString(KEY_PUSH_TOKEN, value).apply()

        override var pushTokenSent: String?
            get() = prefs.getString(KEY_PUSH_TOKEN_SENT, null)
            set(value) = prefs.edit().putString(KEY_PUSH_TOKEN_SENT, value).apply()

        /**
         * Clears the session but keeps [deviceId] and [pushToken] — neither the
         * device nor the token FCM issued it has changed.
         *
         * [pushTokenSent] does go, so the next sign-in re-registers. The server
         * files a push token under the device id, so signing in as somebody else
         * on this phone has to move that row or it would keep being woken for the
         * previous account's lists (M2).
         */
        fun clearSession() {
            prefs
                .edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_EMAIL)
                .remove(KEY_PUSH_TOKEN_SENT)
                .apply()
            session.value = false
        }

        private companion object {
            const val KEY_DEVICE_ID = "device-id"
            const val KEY_ACCESS_TOKEN = "access-token"
            const val KEY_REFRESH_TOKEN = "refresh-token"
            const val KEY_USER_ID = "user-id"
            const val KEY_EMAIL = "email"
            const val KEY_PUSH_TOKEN = "push-token"
            const val KEY_PUSH_TOKEN_SENT = "push-token-sent"
        }
    }
