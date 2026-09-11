package za.co.dielys.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        ListAccentEntity::class,
        TaskEntity::class,
        OutboxEntity::class,
        SyncStateEntity::class,
    ],
    version = DielysDatabase.VERSION,
    exportSchema = true,
)
abstract class DielysDatabase : RoomDatabase() {
    abstract fun lists(): ListDao

    abstract fun listAccents(): ListAccentDao

    abstract fun tasks(): TaskDao

    abstract fun outbox(): OutboxDao

    abstract fun syncState(): SyncStateDao

    companion object {
        const val VERSION = 4
        const val NAME = "dielys.db"

        /**
         * 1 → 2: per-account list ordering (PROTOCOL.md "Ordering the lists").
         * Additive and nullable, so every existing row keeps its data and simply
         * has no order yet — which sorts it where it sorted before.
         */
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE lists ADD COLUMN position TEXT")
                }
            }

        /**
         * 2 → 3: how many people are on a list, so the UI can tell a shared list
         * from a solo one. Defaulted to 1 — solo — for every existing row; the
         * next membership sync corrects it for any that are actually shared.
         */
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE lists ADD COLUMN member_count INTEGER NOT NULL DEFAULT 1",
                    )
                }
            }

        /**
         * 3 → 4: a colour per list (#57), in its own table because it is local to
         * this phone and the `lists` row is a server replica that gets replaced
         * whole — see [ListAccentEntity].
         *
         * Nothing is backfilled here. Every existing list simply has no colour
         * yet, which the UI renders exactly as it did before (the hashed
         * fallback in `ui/theme/ListAccent.kt`), and `ListAccents` gives them
         * real, non-clashing ones the first time the app runs.
         */
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `list_accent` " +
                            "(`list_id` TEXT NOT NULL, `accent` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`list_id`))",
                    )
                }
            }

        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
