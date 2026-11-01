package za.co.dielys.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VoiceDictionaryTest {
    private fun row(
        title: String,
        list: String,
        uses: Int,
        id: String,
    ) = DictionaryRow(title, list, uses, id)

    @Test
    fun `this list first, then by how often, then how recently`() {
        val rows =
            listOf(
                row("Brood", "other", 9, "a"),
                row("Melk", "here", 2, "b"),
                row("Eiers", "here", 5, "c"),
                row("Kaas", "other", 9, "z"),
            )
        assertEquals(
            listOf("Eiers", "Melk", "Kaas", "Brood"),
            VoiceDictionary.build(rows, "here"),
        )
    }

    @Test
    fun `duplicates fold and keep the newest spelling`() {
        val rows =
            listOf(
                row("melk", "here", 1, "a"),
                row("Melk", "other", 1, "b"),
            )
        assertEquals(listOf("Melk"), VoiceDictionary.build(rows, "here"))
    }

    @Test
    fun `list names follow the items and are capped`() {
        val rows = listOf(row("Melk", "here", 1, "a"))
        val dictionary = VoiceDictionary.build(rows, "here", listOf("Inkopies", "melk"), cap = 2)
        assertEquals(listOf("Melk", "Inkopies"), dictionary)
    }

    @Test
    fun `instant match ignores capitals and spacing and uses the person's spelling`() {
        val dictionary = listOf("Melk 2L", "Brood")
        assertEquals(
            "Melk 2L",
            VoiceDictionary.instantMatch(listOf("x", "  melk   2l "), dictionary),
        )
        assertNull(VoiceDictionary.instantMatch(listOf("kaas"), dictionary))
    }

    @Test
    fun `titles too long for the wire are left out`() {
        val rows = listOf(row("x".repeat(201), "here", 1, "a"), row("Melk", "here", 1, "b"))
        assertEquals(listOf("Melk"), VoiceDictionary.build(rows, "here", listOf("y".repeat(300))))
    }
}
