package za.co.dielys.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import za.co.dielys.domain.InviteLink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An invite that arrived by tapping a link, waiting for a screen to deal with it.
 *
 * The Activity cannot act on it itself: the link may arrive while nobody is
 * signed in, in which case it has to survive a sign-in or a registration before
 * anything can be done with it. So it is held here rather than handed straight
 * to a screen, and [take] is what makes it a one-shot — a link acted on once
 * must not be acted on again on the next rotation.
 */
@Singleton
class PendingInvite
    @Inject
    constructor() {
        private val token = MutableStateFlow<String?>(null)

        val pending: StateFlow<String?> = token.asStateFlow()

        /** Ignores anything that is not an invite, including a null intent data. */
        fun offer(link: String?) {
            val found = link?.let(InviteLink::tokenFrom) ?: return
            token.value = found
        }

        fun take(): String? = token.value.also { token.value = null }
    }
