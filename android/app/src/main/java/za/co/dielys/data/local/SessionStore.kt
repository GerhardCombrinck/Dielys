package za.co.dielys.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
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
    ) : DeviceIdentity {
        private val prefs: SharedPreferences =
            context.getSharedPreferences("dielys-session", Context.MODE_PRIVATE)

        @get:Synchronized
        override val deviceId: String
            get() =
                prefs.getString(KEY_DEVICE_ID, null) ?: Uuid7.generate().also {
                    prefs.edit().putString(KEY_DEVICE_ID, it).apply()
                }

        var accessToken: String?
            get() = prefs.getString(KEY_ACCESS_TOKEN, null)
            set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

        var refreshToken: String?
            get() = prefs.getString(KEY_REFRESH_TOKEN, null)
            set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

        var userId: String?
            get() = prefs.getString(KEY_USER_ID, null)
            set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

        /** Clears the session but keeps [deviceId] — the device has not changed. */
        fun clearSession() {
            prefs
                .edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_USER_ID)
                .apply()
        }

        private companion object {
            const val KEY_DEVICE_ID = "device-id"
            const val KEY_ACCESS_TOKEN = "access-token"
            const val KEY_REFRESH_TOKEN = "refresh-token"
            const val KEY_USER_ID = "user-id"
        }
    }
