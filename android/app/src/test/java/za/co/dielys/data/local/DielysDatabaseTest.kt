package za.co.dielys.data.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.co.dielys.data.inMemoryDatabase

@RunWith(RobolectricTestRunner::class)
class DielysDatabaseTest {
    private val db = inMemoryDatabase()

    @After
    fun close() = db.close()

    @Test
    fun `tasks come back ordered by position then id, byte by byte`() =
        runTest {
            // The two rows sharing "a0V" are the H3.9 case: two devices inserting
            // at the same spot offline produce the same key, and the id decides.
            val positions =
                listOf(
                    "b00" to "018f2f6c-0000-7000-8000-000000000005",
                    "a0V" to "018f2f6c-0000-7000-8000-000000000004",
                    "a0V" to "018f2f6c-0000-7000-8000-000000000003",
                    "Zz" to "018f2f6c-0000-7000-8000-000000000002",
                    "a0" to "018f2f6c-0000-7000-8000-000000000001",
                )
            for ((position, id) in positions) {
                db.tasks().upsert(task(id = id, position = position))
            }

            val ordered = db.tasks().inList(LIST).map { it.position to it.id }

            assertEquals(
                listOf(
                    "Zz" to "018f2f6c-0000-7000-8000-000000000002",
                    "a0" to "018f2f6c-0000-7000-8000-000000000001",
                    "a0V" to "018f2f6c-0000-7000-8000-000000000003",
                    "a0V" to "018f2f6c-0000-7000-8000-000000000004",
                    "b00" to "018f2f6c-0000-7000-8000-000000000005",
                ),
                ordered,
            )
        }

    @Test
    fun `a tombstoned task is kept but not listed`() =
        runTest {
            db.tasks().upsert(task(id = "t1", position = "a0"))
            db.tasks().upsert(
                task(id = "t2", position = "a1").copy(deletedAt = "2026-09-08T09:00:00.000Z"),
            )

            assertEquals(listOf("t1"), db.tasks().inList(LIST).map { it.id })
            // F5.3: the row is still there. A delete is never a DELETE.
            assertEquals("t2", db.tasks().find("t2")?.id)
        }

    @Test
    fun `outbox hands rows back oldest first and skips dead ones`() =
        runTest {
            val first = db.outbox().enqueue(outboxRow("k1"))
            db.outbox().enqueue(outboxRow("k2"))
            db.outbox().markDead(first, "400 malformed")

            assertEquals(listOf("k2"), db.outbox().pending(LIMIT).map { it.idempotencyKey })
            // Marked, not deleted: something the user typed stays visible.
            assertEquals(2, db.outbox().all().size)
        }

    private fun task(
        id: String,
        position: String,
    ) = TaskEntity(
        id = id,
        listId = LIST,
        title = "Milk",
        done = false,
        starred = false,
        position = position,
    )

    private fun outboxRow(key: String) =
        OutboxEntity(
            idempotencyKey = key,
            listId = LIST,
            entityType = "task",
            entityId = "t1",
            body = "{}",
            createdAt = 0,
        )

    private companion object {
        const val LIST = "018f2f6c-0000-7000-8000-00000000000a"
        const val LIMIT = 10
    }
}
