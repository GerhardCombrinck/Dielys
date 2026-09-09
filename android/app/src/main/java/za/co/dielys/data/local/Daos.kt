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
    @Query("SELECT * FROM lists WHERE deleted_at IS NULL ORDER BY title, id")
    fun observeAll(): Flow<List<ListEntity>>

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
}

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
