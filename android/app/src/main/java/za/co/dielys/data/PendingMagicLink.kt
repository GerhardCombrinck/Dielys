package za.co.dielys.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import za.co.dielys.domain.MagicLinkUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A sign-in token that arrived by tapping a magic link, waiting for a screen
 * to redeem it (ADR 0005). Same shape as [PendingInvite] and for the same
 * reason: the Activity cannot call `/auth/magic/verify` itself — that is a
 * screen's job, and the link can arrive before any screen exists to receive
 * it (a cold start straight from the email app).
 */
@Singleton
class PendingMagicLink
    @Inject
    constructor() {
        private val token = MutableStateFlow<String?>(null)

        val pending: StateFlow<String?> = token.asStateFlow()

        /** Ignores anything that is not a magic link, including a null intent
         * data or an invite link handed to it by mistake. */
        fun offer(link: String?) {
            val found = link?.let(MagicLinkUrl::tokenFrom) ?: return
            token.value = found
        }

        fun take(): String? = token.value.also { token.value = null }
    }
