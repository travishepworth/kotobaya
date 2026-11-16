package kotobaya.contracts.cards

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonValue

/**
 * ADR-035 "one card per word": the single rule that decides whether a card kind is **retired**.
 * Every platform (server, Android, web through the server) asks only this object; nobody re-states
 * the rule.
 *
 * A card is retired iff its `kind` is [READING], its note's base type is [NoteBaseType.VOCAB]
 * (built-in vocab or any custom type with `base_type = vocab`) and the synced setting
 * [SETTING_READING_ENABLED] is not JSON `true`. Nothing is stored about it: no op, no column, no
 * migration. Turning the setting on brings the very same cards (and their review history and
 * schedule) back; turning it off hides them again.
 *
 * Consequences (normative, each implemented where named):
 * - [CardGenerator] never returns a retired kind (`kindsForNewNote`, `missingKinds`), whatever the
 *   template `default` flag, a deck's `card_kinds` or an explicit request says. Notes created
 *   while retired never get the kind later (ADR-011: no backfill).
 * - [kotobaya.contracts.queue.QueueBuilder] treats a retired candidate as absent: never served,
 *   counted, drawn, used for or subject to sibling burying, and today's reviews of it are ignored
 *   (`QueueInput.readingEnabled`).
 * - [kotobaya.contracts.queue.WordStatusRules.autoStatus] ignores retired cards; every replica
 *   re-derives the `auto_status` of every vocab entry when it applies a `settings.set` of
 *   [SETTING_READING_ENABLED] (OPS.md `settings.set`).
 * - Reviews of a retired card (including unsynced offline ones from older clients) are accepted
 *   and replayed on that card as usual; they never feed another card. Browse and search
 *   (`kind:reading`) still list retired cards.
 *
 * Example: setting absent → `isRetired(VOCAB, "reading", readingEnabled(null))` = true;
 * `isRetired(VOCAB, "meaning", false)` = false; `isRetired(PHRASE, "reading", false)` = false;
 * setting `true` → `isRetired(VOCAB, "reading", true)` = false.
 */
public object CardKinds {
    /** The vocab kind that ADR-035 retires: `reading` (kanji → kana). */
    public const val READING: String = "reading"

    /**
     * Settings key (SCHEMA.md 6, 6.4): `cards.reading_enabled`, bool, default false. Synced by
     * `settings.set`; written by the UI toggle "Also create reading cards".
     */
    public const val SETTING_READING_ENABLED: String = "cards.reading_enabled"

    /**
     * Default of [SETTING_READING_ENABLED]: **false** (reading cards off). A missing row, JSON
     * `null` and every invalid value mean this default (SCHEMA.md 6.1). This deliberately reverses
     * the "missing key = true" wording of the first ADR-035 draft: the user wants reading cards off
     * unless they turn them on, so the old behaviour comes back only by storing `true`.
     */
    public const val READING_ENABLED_DEFAULT: Boolean = false

    /**
     * The typed value of [SETTING_READING_ENABLED] from its stored JSON value by the SCHEMA.md 6.1
     * `bool` test: true iff [value] is JSON `true`; null (no row), JSON `null`, `false`, `1`,
     * `"true"` and anything else → [READING_ENABLED_DEFAULT] (false). Loaders that already hold a
     * typed bool may skip this.
     */
    public fun readingEnabled(value: JsonValue?): Boolean = value == JsonBool(true)

    /**
     * True iff a card of [kind] on a note of [baseType] is retired under [readingEnabled] (the typed
     * value of [SETTING_READING_ENABLED]): `kind == "reading" && baseType == VOCAB && !readingEnabled`.
     * Kind comparison is exact (kinds are lower-case `^[a-z][a-z0-9_]*$`).
     */
    public fun isRetired(baseType: NoteBaseType, kind: String, readingEnabled: Boolean): Boolean =
        !readingEnabled && baseType == NoteBaseType.VOCAB && kind == READING
}
