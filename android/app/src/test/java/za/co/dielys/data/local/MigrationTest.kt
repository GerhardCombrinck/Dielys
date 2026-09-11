package za.co.dielys.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * G2: a schema change ships with a test that populates the old schema, migrates,
 * and asserts the data survived.
 *
 * This is not ceremony. `fallbackToDestructiveMigration()` is never called, so a
 * migration that throws leaves the app unable to open its own database — with the
 * outbox, which is unsent user data, inside it.
 *
 * The old schema is built from the **exported** `schemas/<version>.json` rather
 * than from DDL retyped here: a copy would drift, and then the test would prove
 * that the migration works against a database no user ever had. Opening the
 * result with Room is the validation step — Room compares the live schema against
 * the one it generated and refuses to open a database that does not match.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `1 to 2 keeps every list and its unsent edits, and leaves the order unset`() =
        runTest {
            val file = File(context.cacheDir, "migration-test.db")
            file.delete()

            createSchema(file, version = 1)

            openAtVersion1(file).use { old ->
                old.execSQL(
                    """
                    INSERT INTO lists (id, title, background_photo_url, deleted_at, updated_at, role)
                    VALUES ('list-1', 'Inkopies', NULL, NULL, '2026-09-01T06:00:00.000Z', 'owner')
                    """.trimIndent(),
                )
                // An unsent edit — the thing a destructive migration throws away.
                old.execSQL(
                    """
                    INSERT INTO outbox
                      (idempotency_key, list_id, entity_type, entity_id, body, created_at,
                       attempts, dead)
                    VALUES ('key-1', 'list-1', 'task', 'task-1', '{}', 1, 0, 0)
                    """.trimIndent(),
                )
            }

            val db =
                Room
                    .databaseBuilder(context, DielysDatabase::class.java, file.absolutePath)
                    .addMigrations(*DielysDatabase.MIGRATIONS)
                    .build()

            val list = db.lists().find("list-1")
            assertEquals("Inkopies", list?.title)
            assertEquals("owner", list?.role)
            // Null, not invented: nothing has been dragged, so the list sorts
            // where it sorted before the upgrade.
            assertNull(list?.position)

            assertEquals(
                listOf("key-1"),
                db
                    .outbox()
                    .all()
                    .map { it.idempotencyKey },
            )
            db.close()
        }

    @Test
    fun `2 to 3 defaults every existing list to solo`() =
        runTest {
            val file = File(context.cacheDir, "migration-test-2-3.db")
            file.delete()

            createSchema(file, version = 2)

            openAtVersion1(file).use { old ->
                old.execSQL(
                    """
                    INSERT INTO lists (id, title, background_photo_url, deleted_at, updated_at, role)
                    VALUES ('list-1', 'Inkopies', NULL, NULL, '2026-09-01T06:00:00.000Z', 'owner')
                    """.trimIndent(),
                )
            }

            val db =
                Room
                    .databaseBuilder(context, DielysDatabase::class.java, file.absolutePath)
                    .addMigrations(*DielysDatabase.MIGRATIONS)
                    .build()

            val list = db.lists().find("list-1")
            assertEquals("Inkopies", list?.title)
            // Nobody has told this row otherwise yet, so it reads as solo — the
            // next membership sync corrects it if that turns out to be wrong.
            assertEquals(1, list?.memberCount)
            db.close()
        }

    @Test
    fun `3 to 4 keeps every list, and leaves them with no colour to be assigned`() =
        runTest {
            val file = File(context.cacheDir, "migration-test-3-4.db")
            file.delete()

            createSchema(file, version = 3)

            openAtVersion1(file).use { old ->
                old.execSQL(
                    """
                    INSERT INTO lists
                      (id, title, background_photo_url, deleted_at, updated_at, role,
                       position, member_count)
                    VALUES ('list-1', 'Inkopies', NULL, NULL, '2026-09-01T06:00:00.000Z',
                            'owner', 'a0', 2)
                    """.trimIndent(),
                )
            }

            val db =
                Room
                    .databaseBuilder(context, DielysDatabase::class.java, file.absolutePath)
                    .addMigrations(*DielysDatabase.MIGRATIONS)
                    .build()

            val list = db.lists().find("list-1")
            assertEquals("Inkopies", list?.title)
            assertEquals("a0", list?.position)
            assertEquals(2, list?.memberCount)

            // Nothing is backfilled by the migration itself: the list comes out
            // the other side uncoloured, which is what puts it in front of
            // ListAccents on the next launch. Until then the screen draws the
            // hashed fallback, so it looks exactly as it did before the upgrade.
            assertEquals(listOf("list-1"), db.listAccents().observeUnassigned().first())
            db.close()
        }

    /** Writes the tables and indices the exported schema for [version] declares. */
    private fun createSchema(
        file: File,
        version: Int,
    ) {
        val exported =
            Json
                .parseToJsonElement(schemaFile(version).readText())
                .jsonObject["database"]!!
                .jsonObject

        openAtVersion1(file).use { db ->
            for (entity in exported["entities"]!!.jsonArray) {
                val table = entity.jsonObject
                db.execSQL(
                    table["createSql"]!!
                        .jsonPrimitive.content
                        .replace("\${TABLE_NAME}", table["tableName"]!!.jsonPrimitive.content),
                )
                for (index in table["indices"]?.jsonArray.orEmpty()) {
                    db.execSQL(
                        index.jsonObject["createSql"]!!
                            .jsonPrimitive.content
                            .replace(
                                "\${TABLE_NAME}",
                                table["tableName"]!!.jsonPrimitive.content,
                            ),
                    )
                }
            }

            // What Room checks on open: the version, and the hash of the schema it
            // was built from. Without both it treats the file as corrupt.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(exported["identityHash"]!!.jsonPrimitive.content),
            )
            db.version = version
        }
    }

    private fun openAtVersion1(file: File) =
        android.database.sqlite.SQLiteDatabase
            .openOrCreateDatabase(file, null)

    private fun schemaFile(version: Int): File {
        val name = "za.co.dielys.data.local.DielysDatabase/$version.json"
        // Robolectric runs from the module directory; the schemas are committed
        // beside the source they were generated from.
        return listOf(File("schemas/$name"), File("app/schemas/$name")).first { it.exists() }
    }
}

private inline fun <T : android.database.sqlite.SQLiteDatabase, R> T.use(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }
