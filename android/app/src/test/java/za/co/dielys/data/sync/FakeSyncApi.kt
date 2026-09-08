package za.co.dielys.data.sync

import za.co.dielys.data.remote.ApiException
import za.co.dielys.data.remote.CatchUpResponse
import za.co.dielys.data.remote.ChangeEnvelope
import za.co.dielys.data.remote.DielysJson
import za.co.dielys.data.remote.ErrorCode
import za.co.dielys.data.remote.ListChange
import za.co.dielys.data.remote.ListMutation
import za.co.dielys.data.remote.Membership
import za.co.dielys.data.remote.Mutation
import za.co.dielys.data.remote.MutationAck
import za.co.dielys.data.remote.SyncApi
import za.co.dielys.data.remote.Task
import za.co.dielys.data.remote.TaskChange
import za.co.dielys.data.remote.TaskList
import za.co.dielys.data.remote.TaskMutation
import za.co.dielys.domain.Timestamps
import java.io.IOException

/**
 * A `ListRoom` small enough to hold in a test: a monotonic seq per list, a
 * changelog, and an idempotency map that returns the original result on
 * redelivery.
 *
 * The point is not to reimplement the server — the server has its own 168 tests
 * against real `workerd`. It is to make the H3 offline scenarios expressible on the
 * JVM, where "the network went away mid-drain" is one boolean rather than an
 * emulator and a firewall rule (H1).
 */
class FakeSyncApi : SyncApi {
    /** Flip to false to make every call fail the way no network fails. */
    var online: Boolean = true

    /**
     * Applies the next mutation and then loses the response, which is what a
     * process killed mid-drain looks like from the client side (H3.4).
     */
    var loseNextResponse: Boolean = false

    /** Makes the next mutate fail with a 4xx that no retry can fix. */
    var rejectNextWith: String? = null

    /** Makes every call fail the way an expired session fails. */
    var unauthorized: Boolean = false

    /** Lowered by tests that need to see catch-up paginate. */
    var pageSize: Int = 500

    val sentBodies: MutableList<String> = mutableListOf()
    val claims: MutableList<String> = mutableListOf()

    private val heads = mutableMapOf<String, Long>()
    private val changelog = mutableMapOf<String, MutableList<ChangeEnvelope>>()
    private val acks = mutableMapOf<String, MutationAck>()
    private val tasks = mutableMapOf<String, Task>()
    private val lists = mutableMapOf<String, TaskList>()
    private var stampMillis = 1_760_000_000_000L

    override suspend fun mutate(
        listId: String,
        body: String,
    ): MutationAck {
        gate()
        sentBodies += body
        val mutation = DielysJson.wire.decodeFromString(Mutation.serializer(), body)
        if (mutation.listId != listId) {
            throw ApiException.Rejected(status = 400, code = ErrorCode.LIST_MISMATCH)
        }
        rejectNextWith?.let { code ->
            rejectNextWith = null
            throw ApiException.Rejected(status = 400, code = code)
        }

        // F5.2: the same key never produces a second changelog row, and the answer
        // is the original one, at the original seq.
        acks[mutation.idempotencyKey]?.let { original ->
            return original.copy(duplicate = true)
        }

        val ack = MutationAck(mutation.idempotencyKey, record(mutation), duplicate = false)
        acks[mutation.idempotencyKey] = ack
        if (loseNextResponse) {
            loseNextResponse = false
            throw ApiException.Transport(IOException("connection reset after apply"))
        }
        return ack
    }

    override suspend fun changes(
        listId: String,
        since: Long,
    ): CatchUpResponse {
        gate()
        val pending = changelog[listId].orEmpty().filter { it.seq > since }
        val page = pending.take(pageSize)
        return CatchUpResponse(
            listId = listId,
            since = since,
            changes = page,
            maxSeq = heads[listId] ?: 0L,
            truncated = page.size < pending.size,
        )
    }

    override suspend fun claimList(listId: String) {
        gate()
        claims += listId
    }

    override suspend fun memberships(): List<Membership> = emptyList()

    /** A change made by the other device, already on the server. */
    fun otherDevice(mutation: Mutation): ChangeEnvelope = record(mutation)

    private fun gate() {
        if (unauthorized) throw ApiException.Unauthorized(ErrorCode.UNAUTHORIZED)
        if (!online) throw ApiException.Transport(IOException("no network"))
    }

    private fun record(mutation: Mutation): ChangeEnvelope {
        val stamp = Timestamps.iso(stampMillis++)
        val seq = (heads[mutation.listId] ?: 0L) + 1
        val change =
            when (mutation) {
                is TaskMutation -> taskChange(mutation, seq, stamp)
                is ListMutation -> listChange(mutation, seq, stamp)
            }
        heads[mutation.listId] = seq
        changelog.getOrPut(mutation.listId) { mutableListOf() } += change
        return change
    }

    private fun taskChange(
        mutation: TaskMutation,
        seq: Long,
        stamp: String,
    ): TaskChange {
        val patch = mutation.patch
        val current = tasks[mutation.entityId]
        val entity =
            if (current == null) {
                Task(
                    id = mutation.entityId,
                    listId = mutation.listId,
                    title = patch.title ?: incompleteCreate(),
                    done = patch.done ?: false,
                    starred = patch.starred ?: false,
                    position = patch.position ?: incompleteCreate(),
                    deletedAt = patch.deletedAt,
                    updatedAt = stamp,
                )
            } else {
                current.copy(
                    title = patch.title ?: current.title,
                    done = patch.done ?: current.done,
                    starred = patch.starred ?: current.starred,
                    position = patch.position ?: current.position,
                    // F5.3: a tombstone never lifts.
                    deletedAt = current.deletedAt ?: patch.deletedAt,
                    updatedAt = stamp,
                )
            }
        tasks[entity.id] = entity
        return TaskChange(
            seq = seq,
            listId = mutation.listId,
            idempotencyKey = mutation.idempotencyKey,
            deviceId = mutation.deviceId,
            serverTimestamp = stamp,
            entity = entity,
        )
    }

    private fun listChange(
        mutation: ListMutation,
        seq: Long,
        stamp: String,
    ): ListChange {
        val patch = mutation.patch
        val current = lists[mutation.entityId]
        val entity =
            if (current == null) {
                TaskList(
                    id = mutation.entityId,
                    title = patch.title ?: incompleteCreate(),
                    backgroundPhotoUrl = patch.backgroundPhotoUrl,
                    deletedAt = patch.deletedAt,
                    updatedAt = stamp,
                )
            } else {
                current.copy(
                    title = patch.title ?: current.title,
                    backgroundPhotoUrl = patch.backgroundPhotoUrl ?: current.backgroundPhotoUrl,
                    deletedAt = current.deletedAt ?: patch.deletedAt,
                    updatedAt = stamp,
                )
            }
        lists[entity.id] = entity
        return ListChange(
            seq = seq,
            listId = mutation.listId,
            idempotencyKey = mutation.idempotencyKey,
            deviceId = mutation.deviceId,
            serverTimestamp = stamp,
            entity = entity,
        )
    }

    private fun incompleteCreate(): Nothing =
        throw ApiException.Rejected(status = 400, code = ErrorCode.INCOMPLETE_CREATE)
}
