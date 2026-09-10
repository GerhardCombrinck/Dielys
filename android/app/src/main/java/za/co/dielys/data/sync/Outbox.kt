package za.co.dielys.data.sync

import za.co.dielys.data.local.OutboxEntity
import za.co.dielys.data.remote.DielysJson
import za.co.dielys.data.remote.ListMutation
import za.co.dielys.data.remote.ListPatch
import za.co.dielys.data.remote.Mutation
import za.co.dielys.data.remote.SetListPositionRequest
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

    /**
     * `POST /auth/memberships/position` — where a list sits in this account's
     * own order. Queues like the rest so a drag made on a train still lands, but
     * it is not a list mutation: it goes to `UsersRoom`, not to the changelog,
     * and the other member never hears about it.
     */
    const val ORDER = "order"
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

        /**
         * Where [listId] sits in this account's own order.
         *
         * The body is the request, stored the same way a mutation's is, so the
         * drain stays "send what was recorded" for every kind of row. There is no
         * idempotency key in it — the endpoint is last-write-wins on one column
         * this account owns — but the row still carries one as its unique key,
         * because the outbox table requires it.
         */
        fun order(
            listId: String,
            position: String,
        ): OutboxEntity {
            val request = SetListPositionRequest(listId = listId, position = position)
            return row(
                OutboxKind.ORDER,
                listId,
                listId,
                Uuid7.generate(clock.nowMillis()),
                DielysJson.outbound.encodeToString(SetListPositionRequest.serializer(), request),
            )
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
