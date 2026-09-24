package za.co.dielys.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A list somebody asked to open by tapping its notification (ADR 0012), held
 * until the screen can act on it — the same one-shot handoff [PendingInvite]
 * makes for a tapped invite, for the same reason: the Activity gets the intent,
 * but only the composed app knows which list is open.
 */
@Singleton
class PendingListOpen
    @Inject
    constructor() {
        private val listId = MutableStateFlow<String?>(null)

        val pending: StateFlow<String?> = listId.asStateFlow()

        fun offer(id: String?) {
            if (id != null) listId.value = id
        }

        fun take(): String? = listId.value.also { listId.value = null }
    }
