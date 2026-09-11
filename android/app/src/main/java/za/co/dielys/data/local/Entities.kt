package za.co.dielys.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import za.co.dielys.data.remote.MembershipRole

/**
 * The local database is the only thing the UI reads (E1). Everything here is
 * either a replica of server state or the outbox that will make it so.
 *
 * Timestamps that participate in ordering are stored as the server sent them —
 * ISO-8601 UTC strings, which sort lexicographically. Device clocks are never
 * trusted for ordering (F5.9), so there is no local `updatedAt` anywhere.
 */

@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "background_photo_url") val backgroundPhotoUrl: String? = null,
    /** Tombstone (F5.3). A deleted row is kept, never removed. */
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    /** Server timestamp, null while the row is only an optimistic local write. */
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    /** `owner` or `member`, from `/auth/memberships`. Null until that call lands. */
    @ColumnInfo(name = "role") val role: String? = null,
    /**
     * Where this list sits in *this account's* order — the same fractional index
     * tasks use (F5.5), held on the membership server-side rather than on the
     * list, because the other person on a shared list keeps their own order.
     *
     * Null on a list nobody has dragged yet. Those sort after every positioned
     * one, so a list that arrives by invite lands at the bottom.
     */
    @ColumnInfo(name = "position") val position: String? = null,
    /** How many people are on this list, this device's account included.
     *  Defaults to 1 — solo — until membership sync says otherwise. */
    @ColumnInfo(name = "member_count", defaultValue = "1") val memberCount: Int = 1,
) {
    /**
     * L3: only the owner may invite. Null — a list whose membership has not been
     * fetched yet — reads as not owned, so the option is missing until the answer
     * is known rather than offered and then refused.
     */
    val ownedByMe: Boolean get() = role == MembershipRole.OWNER

    /** Sync status is only interesting once someone else can make the local
     *  copy go stale — a solo list never has anything to be behind on. */
    val isShared: Boolean get() = memberCount > 1
}

/**
 * What colour a list's dot is, on this phone only (#57).
 *
 * Its own table rather than a column on [ListEntity], for two reasons. The
 * `lists` row is a replica the server owns and `ListDao.upsert` replaces it
 * whole, so a colour held there would be wiped by the next change that arrived
 * for that list. And the colour is deliberately **not** shared: the other
 * person on a shared list picks their own, so it is not in `protocol/` and
 * never reaches the outbox. A separate table says both of those out loud.
 *
 * [accent] is an index into the palette (`ui/theme/ListAccent.kt`), not an ARGB
 * value — the palette can be retuned for legibility without rewriting every
 * row, and the picker can still tell which swatch is the current one.
 */
@Entity(tableName = "list_accent")
data class ListAccentEntity(
    @PrimaryKey @ColumnInfo(name = "list_id") val listId: String,
    @ColumnInfo(name = "accent") val accent: Int,
)

@Entity(
    tableName = "tasks",
    indices = [Index(value = ["list_id", "position", "id"])],
)
data class TaskEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "list_id") val listId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "done") val done: Boolean,
    @ColumnInfo(name = "starred") val starred: Boolean,
    /** Fractional index (F5.5). Opaque, ordered by plain byte comparison. */
    @ColumnInfo(name = "position") val position: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    /** Server timestamp, null while the row is only an optimistic local write. */
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)

/**
 * One pending mutation. The row holds the exact JSON that will be POSTed, built
 * once when the user acted and resent byte-for-byte on every retry — regenerating
 * it would regenerate the idempotency key, and F5.2 would buy nothing.
 *
 * Ordering is by [id], which SQLite hands out monotonically. Two mutations for the
 * same entity must reach the server in the order the user made them, because the
 * server resolves conflicts by arrival timestamp (F5.4).
 */
@Entity(
    tableName = "outbox",
    indices = [Index(value = ["idempotency_key"], unique = true)],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "list_id") val listId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    /** The serialised `Mutation`, ready to send. */
    @ColumnInfo(name = "body") val body: String,
    /** Local wall clock, for diagnostics only — never for ordering (F5.9). */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    /**
     * Set when the server rejected the row with a code no retry can fix. The row
     * is kept rather than deleted: silently dropping something the user typed is
     * worse than leaving it visible and stuck.
     */
    @ColumnInfo(name = "dead") val dead: Boolean = false,
)

/**
 * The per-list cursor. Advances only after the change it counts is committed
 * locally (F5.8) — which is why every write of this row happens inside the same
 * transaction as the change itself, never in a separate call.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "list_id") val listId: String,
    @ColumnInfo(name = "cursor") val cursor: Long,
    /** Highest seq the server has told us about. Only ever a hint for the UI. */
    @ColumnInfo(name = "server_max_seq") val serverMaxSeq: Long = 0,
)
