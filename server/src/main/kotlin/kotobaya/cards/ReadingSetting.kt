package kotobaya.cards

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.CardKinds
import kotobaya.server.json
import kotobaya.sync.SyncJson
import org.jooq.DSLContext

/**
 * The typed setting `cards.reading_enabled` (ADR-035, SCHEMA.md 6.4) as the server replica stands now: the 6.1 `bool`
 * test via [CardKinds.readingEnabled]; a missing row, JSON null and every invalid value mean false. Read once per
 * operation and passed to the core (generator calls, `QueueInput`, `autoStatus`).
 */
internal object ReadingSetting {
    fun load(dsl: DSLContext): Boolean {
        val text = dsl.fetchOptional("select value::text from app.settings where key = ?", CardKinds.SETTING_READING_ENABLED)
            .map { it.get(0, String::class.java) }.orElse(null) ?: return CardKinds.readingEnabled(null)
        return CardKinds.readingEnabled(SyncJson.fromJackson(json.readTree(text)))
    }

    /**
     * SQL predicate "card [card] of note [note] is not retired" ([CardKinds.isRetired] in SQL; `note.type` is the
     * note's base type). Constant text, no bind values.
     */
    fun notRetiredSql(readingEnabled: Boolean, card: String = "c", note: String = "n"): String =
        if (readingEnabled) "true" else "not ($card.kind = '${CardKinds.READING}' and $note.type = '${NoteBaseType.VOCAB.wire}')"
}
