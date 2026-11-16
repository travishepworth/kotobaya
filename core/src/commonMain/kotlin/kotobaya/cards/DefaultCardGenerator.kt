package kotobaya.cards

import kotobaya.contracts.cards.CardGenerator
import kotobaya.contracts.cards.CardKinds
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.queue.AddKanjiMode

/**
 * Reference [CardGenerator]. Pure and deterministic; card ids are minted by the caller.
 *
 * "Create exactly these kinds" (e.g. imports, ADR-009) is `kindsForNewNote(type, listOf("meaning"))`.
 * ADR-035: retired kinds ([CardKinds.isRetired]) are filtered out of both lists.
 * Toggling an optional kind on = [missingKinds] with the kind requested; toggling off creates nothing
 * and the generator never reports deletions (suspend/delete is a user action).
 */
public object DefaultCardGenerator : CardGenerator {
    override fun kindsForNewNote(noteType: NoteTypeInfo, deckCardKinds: List<String>?, readingEnabled: Boolean): List<String> {
        val kinds = if (deckCardKinds == null) noteType.templates.filter { it.isDefault }.map { it.kind } else existing(noteType, deckCardKinds)
        return kinds.filterNot { CardKinds.isRetired(noteType.baseType, it, readingEnabled) }
    }

    override fun missingKinds(
        noteType: NoteTypeInfo,
        liveKinds: Set<String>,
        requested: List<String>,
        readingEnabled: Boolean,
    ): List<String> =
        existing(noteType, requested).filter { it !in liveKinds && !CardKinds.isRetired(noteType.baseType, it, readingEnabled) }

    private fun existing(noteType: NoteTypeInfo, kinds: List<String>): List<String> {
        val known = noteType.templates.map { it.kind }.toSet()
        return kinds.filter { it in known }.distinct()
    }

    override fun kanjiOf(expression: String): List<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            val cp: Int
            val len: Int
            if (c.isHighSurrogate() && i + 1 < expression.length && expression[i + 1].isLowSurrogate()) {
                cp = 0x10000 + ((c.code - 0xD800) shl 10) + (expression[i + 1].code - 0xDC00)
                len = 2
            } else {
                cp = c.code
                len = 1
            }
            if (isKanji(cp)) out.add(expression.substring(i, i + len))
            i += len
        }
        return out.toList()
    }

    private fun isKanji(cp: Int): Boolean =
        cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF || cp in 0x20000..0x2FA1F

    override fun kanjiToAdd(expression: String, existingKanji: Set<String>, mode: AddKanjiMode): List<String> =
        if (mode == AddKanjiMode.OFF) emptyList() else kanjiOf(expression).filter { it !in existingKanji }
}
