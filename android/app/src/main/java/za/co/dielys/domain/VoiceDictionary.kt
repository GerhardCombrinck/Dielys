package za.co.dielys.domain

/**
 * One item title as Room holds it, folded per list: how many rows carry it (each
 * week's "Melk" is its own row, so the count is how often it was added) and the
 * newest row's id. Ids are UUIDv7, so the largest is the most recent.
 */
data class DictionaryRow(
    val title: String,
    val listId: String?,
    val uses: Int,
    val lastId: String,
)

/**
 * The words a hold of the mic is matched against (docs/VOICE.md "The dictionary").
 * Pure (E1): built from rows the caller read, so it runs on the JVM.
 */
object VoiceDictionary {
    const val CAP = 200

    /** The wire limit on one entry (protocol `MAX_VOICE_TEXT_LENGTH`); longer titles are not words you say. */
    const val MAX_ENTRY_LENGTH = 200

    /** Capitals, surrounding and repeated spaces ignored. */
    fun normalise(text: String): String =
        buildString {
            var gap = false
            for (c in text.trim().lowercase()) {
                if (c.isWhitespace()) {
                    if (!gap) append(' ')
                    gap = true
                } else {
                    append(c)
                    gap = false
                }
            }
        }

    /**
     * This list's items first, then everything else; within each, most often added,
     * then most recent. Duplicates fold into one entry that keeps the newest
     * spelling. List names go last, since they are the least likely thing said.
     */
    fun build(
        rows: List<DictionaryRow>,
        currentListId: String?,
        listNames: List<String> = emptyList(),
        cap: Int = CAP,
    ): List<String> {
        class Entry(
            var title: String,
            var lastId: String,
            var uses: Int,
            var here: Boolean,
        )

        val byKey = LinkedHashMap<String, Entry>()
        for (row in rows) {
            val key = normalise(row.title)
            if (key.isEmpty() || row.title.length > MAX_ENTRY_LENGTH) continue
            val entry = byKey.getOrPut(key) { Entry(row.title, row.lastId, 0, false) }
            entry.uses += row.uses
            if (row.listId == currentListId) entry.here = true
            if (row.lastId > entry.lastId) {
                entry.lastId = row.lastId
                entry.title = row.title
            }
        }
        val ranked =
            byKey.values
                .sortedWith(
                    compareByDescending<Entry> { it.here }
                        .thenByDescending { it.uses }
                        .thenByDescending { it.lastId },
                ).map { it.title }
        val seen = ranked.map { normalise(it) }.toMutableSet()
        val names =
            listNames.filter {
                it.isNotBlank() &&
                    it.length <= MAX_ENTRY_LENGTH &&
                    seen.add(normalise(it))
            }
        return (ranked + names).take(cap)
    }

    /**
     * The dictionary item a guess matches exactly (capitals and spacing aside),
     * trying guesses best first, so the field gets the person's own spelling.
     */
    fun instantMatch(
        guesses: List<String>,
        dictionary: List<String>,
    ): String? {
        val byKey = HashMap<String, String>()
        for (item in dictionary) byKey.putIfAbsent(normalise(item), item)
        for (guess in guesses) {
            byKey[normalise(guess)]?.let { return it }
        }
        return null
    }
}
