package za.co.dielys.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The local replica plus the outbox.
 *
 * Every schema change bumps [VERSION] and adds an explicit `Migration` to
 * [MIGRATIONS], with the exported schema JSON in `app/schemas/` committed and a
 * `MigrationTestHelper` test that populates the old schema, migrates, and asserts
 * the data survived (G2).
 *
 * `fallbackToDestructiveMigration()` is never called. It is not a convenience
 * here: the outbox is unsent user data, so wiping the database on a schema
 * mismatch would silently throw away edits the server has never seen.
 */
@Database(
    entities = [
        ListEntity::class,
        TaskEntity::class,
        OutboxEntity::class,
        SyncStateEntity::class,
    ],
    version = DielysDatabase.VERSION,
    exportSchema = true,
)
abstract class DielysDatabase : RoomDatabase() {
    abstract fun lists(): ListDao

    abstract fun tasks(): TaskDao

    abstract fun outbox(): OutboxDao

    abstract fun syncState(): SyncStateDao

    companion object {
        const val VERSION = 1
        const val NAME = "dielys.db"

        /** Empty at version 1. Every later version adds one entry here. */
        val MIGRATIONS = emptyArray<androidx.room.migration.Migration>()
    }
}
