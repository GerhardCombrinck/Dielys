package za.co.dielys.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.SessionStore
import za.co.dielys.data.remote.AccountApi
import za.co.dielys.data.remote.ApiException
import javax.inject.Inject
import javax.inject.Singleton

/** What asking to delete the account did, in terms a screen can act on. */
sealed interface DeleteAccountResult {
    /** Gone on the server and wiped from this phone. The session is over. */
    data object Deleted : DeleteAccountResult

    /** Nothing was sent, so nothing changed. Trying again later costs nothing. */
    data object Offline : DeleteAccountResult

    /** The server answered badly, or the session had already died. Nothing on
     * this phone was touched. [detail] is for the user, not for a log. */
    data class ServerProblem(
        val detail: String,
    ) : DeleteAccountResult
}

/**
 * Deleting the account (ADR 0007, Google Play's account deletion requirement).
 *
 * Unlike [SessionRepository.signOut], which keeps the local replica and outbox
 * because an unsent edit is still the user's, this wipes them: the person asked
 * for their data to be gone, and an outbox that can never be sent again is not
 * worth keeping. The server goes first, so a phone that is offline or refused
 * keeps everything and can simply try again.
 */
@Singleton
class AccountRepository
    @Inject
    constructor(
        private val api: AccountApi,
        private val store: SessionStore,
        private val db: DielysDatabase,
    ) {
        suspend fun deleteAccount(): DeleteAccountResult {
            try {
                api.deleteAccount()
            } catch (_: ApiException.Transport) {
                return DeleteAccountResult.Offline
            } catch (error: ApiException.Unauthorized) {
                return DeleteAccountResult.ServerProblem(error.code ?: "unauthorized")
            } catch (error: ApiException.Rejected) {
                return DeleteAccountResult.ServerProblem(error.code ?: "rejected")
            } catch (error: ApiException.Unavailable) {
                return DeleteAccountResult.ServerProblem("server error ${error.status}")
            }

            // The session first: once the tokens are gone, a sync that was already
            // running has nothing to authenticate with and cannot refill the tables
            // that are about to be emptied.
            store.clearSession()
            withContext(Dispatchers.IO) { db.clearAllTables() }
            return DeleteAccountResult.Deleted
        }
    }
