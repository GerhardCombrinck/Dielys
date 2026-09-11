package za.co.dielys.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reads are `Flow`s, because that is the only way the UI observes data (E1.2). The
 * UI never talks to the network and never blocks on it; a change lands in Room and
 * the screen follows.
 */
@Dao
interface ListDao {
    /**
     * This account's own order. `COALESCE(position, '~')` puts the undragged
     * ones last: '~' is above every character the base-62 position alphabet
     * uses, so no real key sorts past it. Title breaks the tie among those, and
     * id breaks it again — two devices dragging offline can mint the same key
     * (H3.9). BINARY collation throughout, never a locale-aware one.
     */
    @Query(
        """
        SELECT * FROM lists
        WHERE deleted_at IS NULL
        ORDER BY COALESCE(position, '~'), title, id
        """,
    )
    fun observeAll(): Flow<List<ListEntity>>

    /** The same order, once, for working out what a drag landed between. */
    @Query(
        """
        SELECT * FROM lists
        WHERE deleted_at IS NULL
        ORDER BY COALESCE(position, '~'), title, id
        """,
    )
    suspend fun all(): List<ListEntity>

    @Query("SELECT * FROM lists WHERE id = :id")
    fun observe(id: String): Flow<ListEntity?>

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun find(id: String): ListEntity?

    /** Every list this device has heard of, tombstoned ones included — a deleted
     * list still receives changes, and the cursor still has to keep up. */
    @Query("SELECT id FROM lists ORDER BY id")
    suspend fun knownIds(): List<String>

    /**
     * The same set, watched. This is how a list joined by invite gets a socket
     * without anything telling the socket supervisor about it: list discovery
     * writes the row and the query emits.
     */
    @Query("SELECT id FROM lists ORDER BY id")
    fun observeKnownIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(list: ListEntity)

    @Query("UPDATE lists SET role = :role WHERE id = :id")
    suspend fun setRole(
        id: String,
        role: String,
    )

    /**
     * The order alone. Discovery writes this without touching the title, which
     * the changelog owns — the two arrive from different places and neither may
     * clobber the other.
     */
    @Query("UPDATE lists SET position = :position WHERE id = :id")
    suspend fun setPosition(
        id: String,
        position: String?,
    )

    @Query("UPDATE lists SET member_count = :memberCount WHERE id = :id")
    suspend fun setMemberCount(
        id: String,
        memberCount: Int,
    )
}

@Dao
interface TaskDao {
    /**
     * `ORDER BY position, id` under SQLite's default BINARY collation, which is
     * plain byte comparison — the same order the server documents and the web
     * client will have to use. Never a locale-aware collation: it would put "a"
     * before "B" and silently reorder the list.
     */
    @Query(
        """
        SELECT * FROM tasks
        WHERE list_id = :listId AND deleted_at IS NULL
        ORDER BY position, id
        """,
    )
    fun observeInList(listId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun find(id: String): TaskEntity?

    @Query(
        """
        SELECT * FROM tasks
        WHERE list_id = :listId AND deleted_at IS NULL
        ORDER BY position, id
        """,
    )
    suspend fun inList(listId: String): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    /** Feeds the count on the lists screen's row: what is still to do, so a
     * finished list reads 0 rather than however many things it once held. */
    @Query(
        "SELECT list_id AS listId, COUNT(*) AS count FROM tasks WHERE deleted_at IS NULL AND done = 0 GROUP BY list_id",
    )
    fun observeCountsByList(): Flow<List<ListItemCount>>
}

data class ListItemCount(
    val listId: String,
    val count: Int,
)

@Dao
interface OutboxDao {
    /**
     * Oldest first, dead rows skipped. One row at a time: two mutations for the
     * same entity must reach the server in the order the user made them.
     */
    @Query("SELECT * FROM outbox WHERE dead = 0 ORDER BY id LIMIT :limit")
    suspend fun pending(limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE dead = 0")
    fun observePendingCount(): Flow<Int>

    /**
     * Rows the server refused for a reason no retry fixes. They are kept rather
     * than deleted, which only helps if something says so — so the UI shows this.
     * A stuck edit the user cannot see is the same as a lost one.
     */
    @Query("SELECT COUNT(*) FROM outbox WHERE dead = 1")
    fun observeDeadCount(): Flow<Int>

    @Insert
    suspend fun enqueue(row: OutboxEntity): Long

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Whether some *other* not-yet-landed mutation for this entity is still
     * queued. Used to tell a stale echo of one's own earlier tap from the one
     * that actually matches what the screen shows right now — see
     * [za.co.dielys.data.sync.ChangeApplier].
     */
    @Query(
        """
        SELECT COUNT(*) FROM outbox
        WHERE entity_id = :entityId AND idempotency_key != :exceptKey AND dead = 0
        """,
    )
    suspend fun pendingCountForEntity(
        entityId: String,
        exceptKey: String,
    ): Int

    @Query("UPDATE outbox SET attempts = attempts + 1, last_error = :error WHERE id = :id")
    suspend fun recordFailure(
        id: Long,
        error: String,
    )

    @Query(
        "UPDATE outbox SET dead = 1, attempts = attempts + 1, last_error = :error WHERE id = :id",
    )
    suspend fun markDead(
        id: Long,
        error: String,
    )
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE list_id = :listId")
    suspend fun find(listId: String): SyncStateEntity?

    @Query("SELECT cursor FROM sync_state WHERE list_id = :listId")
    suspend fun cursor(listId: String): Long?

    @Query("SELECT * FROM sync_state WHERE list_id = :listId")
    fun observe(listId: String): Flow<SyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)
}
