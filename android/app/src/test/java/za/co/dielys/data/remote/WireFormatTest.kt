package za.co.dielys.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import za.co.dielys.Fixtures

/**
 * The Kotlin wire types against the same fixtures the server and protocol suites
 * read (F4). A field renamed on one side and not the other fails a build here
 * rather than silently dropping somebody's shopping list on the floor.
 *
 * Round trips compare parsed JSON, not bytes. `kotlinx.serialization` writes the
 * class discriminator first and the fixtures do not, and key order carries no
 * meaning in JSON — insisting on it would fail for a reason nobody cares about.
 */
class WireFormatTest {
    @TestFactory
    fun `every change fixture survives a round trip`(): List<DynamicTest> =
        Fixtures.list(CHANGES).map { path ->
            DynamicTest.dynamicTest(path) {
                val raw = Fixtures.read(path)
                val decoded = DielysJson.wire.decodeFromString(ChangeEnvelope.serializer(), raw)
                val encoded = DielysJson.wire.encodeToString(ChangeEnvelope.serializer(), decoded)
                assertEquals(tree(raw), tree(encoded))
            }
        }

    @TestFactory
    fun `every message fixture survives a round trip`(): List<DynamicTest> =
        Fixtures.list(MESSAGES).map { path ->
            DynamicTest.dynamicTest(path) {
                val raw = Fixtures.read(path)
                assertEquals(tree(raw), tree(reencode(raw)))
            }
        }

    @Nested
    inner class ChangesCarryWhatTheServerResolved {
        @Test
        fun `a tombstone arrives as a field, never as an absent entity`() {
            val change = change("task-tombstoned.json")
            val task = (change as TaskChange).entity
            // F5.3: the row is still fully described. A delete is not a deletion.
            assertEquals("Milk", task.title)
            assertNotNull(task.deletedAt)
        }

        @Test
        fun `a title at the protocol maximum decodes whole`() {
            val task = (change("task-max-length-title.json") as TaskChange).entity
            assertEquals(MAX_TITLE_LENGTH, task.title.length)
        }

        @Test
        fun `emoji and dashes survive the trip intact`() {
            val task = (change("task-unicode-title.json") as TaskChange).entity
            assertEquals("Braai vleis 🍖 — vir Saterdag ❤️", task.title)
            assertTrue(task.starred)
        }

        /**
         * The pair the server uses to prove F5.4 breaks ties by device id. If a
         * fixture edit ever separated their timestamps, the tie would stop being
         * tested on either side.
         */
        @Test
        fun `the conflicting update pair still ties on the server timestamp`() {
            val a = change("task-conflicting-update-a.json")
            val b = change("task-conflicting-update-b.json")
            assertEquals(a.serverTimestamp, b.serverTimestamp)
            assertTrue(a.deviceId != b.deviceId)
            assertEquals(
                (a as TaskChange).entity.id,
                (b as TaskChange).entity.id,
            )
        }

        @Test
        fun `a list change carries the list itself`() {
            val change = change("list-created.json") as ListChange
            assertEquals("Groceries", change.entity.title)
            assertEquals(null, change.entity.backgroundPhotoUrl)
        }
    }

    @Nested
    inner class MessagesDecodeToTheRightType {
        @Test
        fun `a duplicate ack is a no-op carrying the original change`() {
            val ack = message("ack-duplicate.json") as MutationAck
            // F5.2: a redelivered key returns the first result, at the first seq.
            assertTrue(ack.duplicate)
            assertEquals(1L, ack.change.seq)
            assertEquals(ack.idempotencyKey, ack.change.idempotencyKey)
        }

        @Test
        fun `a truncated page says so, and an empty one does not`() {
            val page = message("catch-up-response-page.json") as CatchUpResponse
            assertTrue(page.truncated)
            // More behind it than the page holds — the client pulls again from here.
            assertTrue(page.maxSeq > page.changes.last().seq)

            val empty = message("catch-up-response-empty.json") as CatchUpResponse
            assertTrue(empty.changes.isEmpty())
            assertEquals(false, empty.truncated)
        }

        @Test
        fun `hello-ok agrees with the version this build speaks`() {
            val hello = message("hello-ok.json") as ServerHelloOk
            assertEquals(PROTOCOL_VERSION, hello.protocolVersion)
        }

        @Test
        fun `an error code is read as a string, not an enum`() {
            val error = message("error-incomplete-create.json") as ServerError
            // F2: a code from a newer server must not crash a client one release
            // behind, which is why these are never an enum.
            assertEquals(ErrorCode.INCOMPLETE_CREATE, error.code)
            assertNotNull(error.idempotencyKey)

            val rejected = message("hello-error-unsupported-version.json") as ServerHelloError
            assertEquals(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION, rejected.code)
        }
    }

    @Nested
    inner class MutationsOmitWhatTheyDoNotTouch {
        @Test
        fun `a tick sends only the field that changed`() {
            val raw = Fixtures.read("$MESSAGES/mutate-task-tick.json")
            val mutation = mutation(raw)
            assertEquals(true, mutation.patch.done)
            assertEquals(null, mutation.patch.title)

            // F5.4 lives on this: an absent key means "leave alone", so two devices
            // editing different fields of one task offline both survive.
            val keys = tree(reencode(raw)).jsonObject["patch"]?.jsonObject?.keys
            assertEquals(setOf("done"), keys)
        }

        @Test
        fun `a create carries the fields only the client can know`() {
            val raw = Fixtures.read("$MESSAGES/mutate-task-create.json")
            val mutation = mutation(raw)
            // The server invents neither of these (F5.1, F5.5).
            assertEquals("Milk", mutation.patch.title)
            assertEquals("a0", mutation.patch.position)
            assertEquals(PROTOCOL_VERSION, mutation.protocolVersion)
        }
    }

    /**
     * Fixtures name their own type, so the dispatch here is the same one the client
     * makes at runtime rather than a lookup table that could drift from it.
     */
    private fun reencode(raw: String): String =
        when (tree(raw).jsonObject["type"]?.jsonPrimitive?.content) {
            "mutate" ->
                DielysJson.outbound.encodeToString(
                    Mutation.serializer(),
                    DielysJson.wire.decodeFromString(Mutation.serializer(), raw),
                )

            "hello" ->
                DielysJson.wire.encodeToString(
                    ClientHello.serializer(),
                    DielysJson.wire.decodeFromString(ClientHello.serializer(), raw),
                )

            "catch-up" ->
                DielysJson.wire.encodeToString(
                    CatchUpRequest.serializer(),
                    DielysJson.wire.decodeFromString(CatchUpRequest.serializer(), raw),
                )

            else ->
                DielysJson.wire.encodeToString(
                    ServerMessage.serializer(),
                    DielysJson.wire.decodeFromString(ServerMessage.serializer(), raw),
                )
        }

    private fun change(name: String): ChangeEnvelope =
        DielysJson.wire.decodeFromString(
            ChangeEnvelope.serializer(),
            Fixtures.read("$CHANGES/$name"),
        )

    private fun message(name: String): ServerMessage =
        DielysJson.wire.decodeFromString(
            ServerMessage.serializer(),
            Fixtures.read("$MESSAGES/$name"),
        )

    private fun mutation(raw: String): TaskMutation =
        DielysJson.wire.decodeFromString(Mutation.serializer(), raw) as TaskMutation

    private fun tree(raw: String): JsonElement = Json.parseToJsonElement(raw)

    private companion object {
        const val CHANGES = "changes"
        const val MESSAGES = "messages"
    }
}
