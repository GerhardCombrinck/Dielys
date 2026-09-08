package za.co.dielys.data.sync

import za.co.dielys.data.local.OutboxEntity
import za.co.dielys.data.remote.DielysJson
import za.co.dielys.data.remote.ListMutation
import za.co.dielys.data.remote.ListPatch
import za.co.dielys.data.remote.Mutation
import za.co.dielys.data.remote.TaskMutation
import za.co.dielys.data.remote.TaskPatch
import za.co.dielys.domain.Clock
import za.co.dielys.domain.Uuid7
import javax.inject.Inject
import javax.inject.Singleton

/** The kinds of work an outbox row can represent. */
object OutboxKind {
    const val TASK = "task"
    const val LIST = "list"

    /**
     * `POST /lists/{id}` — claiming a client-generated list id as owner. Not a
     * mutation, but it queues like one so it retries like one, and it must reach
     * the server before any mutation for that list.
     */
    const val CLAIM = "claim"
}

/**
 * Builds outbox rows.
 *
 * The idempotency key is generated **here**, once, when the user acts. The drain
 * resends the stored body byte-for-byte and never rebuilds it: a retry that
 * regenerated the key would look like a second mutation to the server, and F5.2
 * would buy nothing.
 */
@Singleton
class OutboxFactory
    @Inject
    constructor(
        private val clock: Clock,
    ) {
        fun task(
            listId: String,
            entityId: String,
            deviceId: String,
            patch: TaskPatch,
        ): OutboxEntity {
            val key = Uuid7.generate(clock.nowMillis())
            val mutation =
                TaskMutation(
                    listId = listId,
                    idempotencyKey = key,
                    deviceId = deviceId,
                    entityId = entityId,
                    patch = patch,
                )
            return row(OutboxKind.TASK, listId, entityId, key, encode(mutation))
        }

        fun list(
            listId: String,
            entityId: String,
            deviceId: String,
            patch: ListPatch,
        ): OutboxEntity {
            val key = Uuid7.generate(clock.nowMillis())
            val mutation =
                ListMutation(
                    listId = listId,
                    idempotencyKey = key,
                    deviceId = deviceId,
                    entityId = entityId,
                    patch = patch,
                )
            return row(OutboxKind.LIST, listId, entityId, key, encode(mutation))
        }

        fun claim(listId: String): OutboxEntity =
            row(OutboxKind.CLAIM, listId, listId, Uuid7.generate(clock.nowMillis()), "{}")

        private fun row(
            kind: String,
            listId: String,
            entityId: String,
            key: String,
            body: String,
        ): OutboxEntity =
            OutboxEntity(
                idempotencyKey = key,
                listId = listId,
                entityType = kind,
                entityId = entityId,
                body = body,
                createdAt = clock.nowMillis(),
            )

        private fun encode(mutation: Mutation): String =
            DielysJson.outbound.encodeToString(Mutation.serializer(), mutation)
    }
