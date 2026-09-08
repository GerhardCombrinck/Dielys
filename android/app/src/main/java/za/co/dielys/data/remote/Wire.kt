package za.co.dielys.data.remote

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The wire contract, mirroring `protocol/src/types.ts`. This file and that one are
 * one unit — a change to either without the other is incomplete (F1).
 *
 * `protocol/fixtures/` is on the unit-test resource path, and `WireFormatTest`
 * round-trips every fixture through these types, so a drift fails a build rather
 * than a shopping trip (F4).
 */

const val PROTOCOL_VERSION = 2

const val MAX_TITLE_LENGTH = 1000
const val MAX_POSITION_LENGTH = 256
const val MAX_URL_LENGTH = 2048
const val MAX_ID_LENGTH = 64

/** How many changes one catch-up page carries. See [CatchUpResponse.truncated]. */
const val CATCH_UP_PAGE_SIZE = 500

object DielysJson {
    /**
     * Inbound and fixture parsing. Unknown fields are ignored, never rejected —
     * that is the additive half of the versioning rules (F2).
     */
    val wire =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * Outbound mutations only. `explicitNulls = false` is what makes a patch mean
     * "only the fields named here" (F5.4): a null property is omitted rather than
     * sent as an explicit null, so two devices editing different fields of the same
     * task while offline send disjoint patches and both survive.
     *
     * The one thing this gives up is sending a deliberate null. The server ignores
     * `deletedAt: null` anyway — tombstones are sticky (F5.3) — so the only field
     * that would want it is `backgroundPhotoUrl`, which no screen can clear yet.
     */
    val outbound =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
}

@Serializable
data class Task(
    val id: String,
    val listId: String,
    val title: String,
    val done: Boolean,
    val starred: Boolean,
    val position: String,
    val deletedAt: String? = null,
    val updatedAt: String,
)

@Serializable
data class TaskList(
    val id: String,
    val title: String,
    val backgroundPhotoUrl: String? = null,
    val deletedAt: String? = null,
    val updatedAt: String,
)

/**
 * The discriminator is explicit rather than inferred from the shape of `entity`,
 * so adding a field to either entity can never start misclassifying old rows.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("entityType")
sealed interface ChangeEnvelope {
    val seq: Long
    val listId: String
    val idempotencyKey: String
    val deviceId: String

    /** Server timestamp. Device clocks are never trusted for ordering (F5.9). */
    val serverTimestamp: String
}

@Serializable
@SerialName("task")
data class TaskChange(
    override val seq: Long,
    override val listId: String,
    override val idempotencyKey: String,
    override val deviceId: String,
    override val serverTimestamp: String,
    val entity: Task,
) : ChangeEnvelope

@Serializable
@SerialName("list")
data class ListChange(
    override val seq: Long,
    override val listId: String,
    override val idempotencyKey: String,
    override val deviceId: String,
    override val serverTimestamp: String,
    val entity: TaskList,
) : ChangeEnvelope

/** An absent key means "leave alone" — see [DielysJson.outbound]. */
@Serializable
data class TaskPatch(
    val title: String? = null,
    val done: Boolean? = null,
    val starred: Boolean? = null,
    val position: String? = null,
    val deletedAt: String? = null,
)

@Serializable
data class ListPatch(
    val title: String? = null,
    val backgroundPhotoUrl: String? = null,
    val deletedAt: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("entityType")
sealed interface Mutation {
    val type: String
    val protocolVersion: Int
    val listId: String

    /** Generated once, at creation time, and resent unchanged on every retry (F5.2). */
    val idempotencyKey: String
    val deviceId: String

    /** Always client-generated (F5.1). The server never mints an id. */
    val entityId: String
}

@Serializable
@SerialName("task")
data class TaskMutation(
    override val listId: String,
    override val idempotencyKey: String,
    override val deviceId: String,
    override val entityId: String,
    val patch: TaskPatch,
    override val type: String = "mutate",
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : Mutation

@Serializable
@SerialName("list")
data class ListMutation(
    override val listId: String,
    override val idempotencyKey: String,
    override val deviceId: String,
    override val entityId: String,
    val patch: ListPatch,
    override val type: String = "mutate",
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : Mutation

@Serializable
data class ClientHello(
    val listId: String,
    val cursor: Long,
    val deviceId: String,
    val type: String = "hello",
    val protocolVersion: Int = PROTOCOL_VERSION,
)

@Serializable
data class CatchUpRequest(
    val listId: String,
    val since: Long,
    val type: String = "catch-up",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ServerMessage

@Serializable
@SerialName("hello-ok")
data class ServerHelloOk(
    val protocolVersion: Int,
    val listId: String,
    val maxSeq: Long,
) : ServerMessage

@Serializable
@SerialName("hello-error")
data class ServerHelloError(
    val code: String,
) : ServerMessage

@Serializable
@SerialName("change")
data class ChangeMessage(
    val change: ChangeEnvelope,
) : ServerMessage

@Serializable
@SerialName("ack")
data class MutationAck(
    val idempotencyKey: String,
    val change: ChangeEnvelope,
    /** True when this key had already been applied — a no-op, not an error (F5.2). */
    val duplicate: Boolean,
) : ServerMessage

@Serializable
@SerialName("catch-up-response")
data class CatchUpResponse(
    val listId: String,
    val since: Long,
    val changes: List<ChangeEnvelope>,
    val maxSeq: Long,
    /** True when more changes remain past this page — pull again from [maxSeq]. */
    val truncated: Boolean,
) : ServerMessage

@Serializable
@SerialName("error")
data class ServerError(
    val code: String,
    val idempotencyKey: String? = null,
) : ServerMessage

/**
 * Kept as strings rather than an enum: a code this build has never heard of must
 * not crash a client that is one release behind (F2).
 */
object ErrorCode {
    const val MALFORMED = "malformed"
    const val UNSUPPORTED_PROTOCOL_VERSION = "unsupported-protocol-version"
    const val UNAUTHORIZED = "unauthorized"
    const val LIST_MISMATCH = "list-mismatch"
    const val INCOMPLETE_CREATE = "incomplete-create"
    const val INTERNAL = "internal"
    const val INVALID_CREDENTIALS = "invalid-credentials"
}
