package kotobaya.android.ankiconnect

/** A canned collection matching the fixtures in `src/test/resources/ankiconnect`. */
class FakeBackend : AnkiConnectBackend {
    val added = mutableListOf<AcNote>()
    val stored = mutableMapOf<String, ByteArray>()
    private val fields = listOf("Expression", "Kana", "Meaning", "Sentence")

    override fun decks() = listOf(AcDeck(1, "Default"), AcDeck(1496198395707, "Mined"))

    override fun models() = listOf(AcModel(1483883011648, "Takoboto Vocab", fields), AcModel(1483883011649, "Basic", listOf("Front", "Back")))

    override fun check(note: AcNote): AcAdd =
        if (note.fields["Expression"] == "食べる") AcAdd.Duplicate else AcAdd.Added(0)

    override fun addNote(note: AcNote): AcAdd {
        val c = check(note)
        if (c !is AcAdd.Added) return c
        added += note
        return AcAdd.Added(1496198395707)
    }

    override fun storeMedia(filename: String, bytes: ByteArray): String {
        stored[filename] = bytes
        return filename
    }

    override fun findNotes(query: String): List<Long> = if ("食べる" in query) listOf(1483959289817) else emptyList()

    override fun notesInfo(ids: List<Long>): List<AcNoteInfo> = ids.filter { it == 1483959289817 }.map {
        AcNoteInfo(
            it,
            "Takoboto Vocab",
            listOf("yomitan"),
            linkedMapOf("Expression" to "食べる", "Kana" to "たべる", "Meaning" to "to eat", "Sentence" to ""),
        )
    }
}
