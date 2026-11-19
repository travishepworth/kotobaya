package kotobaya.contracts.render

import kotobaya.contracts.cards.NoteTypeInfo

/**
 * What [WordAudioResolver] needs to know about one note (ADR-047, C-29). The same three values a
 * host already loads to render the note's cards.
 */
public data class WordAudioInput(
    /** The note's type. Only [NoteTypeInfo.baseType] is read. */
    public val noteType: NoteTypeInfo,
    /** `note.fields` (key → HTML fragment); missing key = `""`. */
    public val fields: Map<String, String>,
    /** Dictionary data of the note's entry, or null (no entry, or not available on this replica). */
    public val dictionary: DictionaryData?,
)

/**
 * The word audio of a note (ADR-047): where the sound of the word itself comes from. API
 * `QueueItem.word_audio.source`.
 */
public sealed interface WordAudio {
    /**
     * The note carries its own audio: the first `[sound:<sha256>]` of its `audio` field. It always
     * wins over generated speech. Example: `audio = "[sound:9f86…0a08]"` → `Note("9f86…0a08")`.
     */
    public data class Note(
        /** Blob hash, 64 lowercase hex digits (`app.blob.hash`). */
        public val blobHash: String,
    ) : WordAudio

    /**
     * No audio in the note: the word is spoken by the TTS engine. The host pairs [text] with the
     * effective voice ([WordAudioResolver.effectiveVoice]) and looks the pair up (server:
     * `app.tts_audio`; phone: the device table `tts_audio`; web: `QueueItem.word_audio.blob_hash` or
     * `resolveSpeech`). Example: 食べる with an empty `reading` field → `Tts("たべる")`.
     */
    public data class Tts(
        /** The speech text: 1 to [WordAudioRules.MAX_TEXT_LENGTH] UTF-16 code units, never blank, no whitespace. */
        public val text: String,
    ) : WordAudio
}

/** Constants of the word-audio rule (SCHEMA.md 6.6). */
public object WordAudioRules {
    /** VOICEVOX style id used when the setting `tts.voice` is absent, null or invalid. */
    public const val DEFAULT_VOICE: String = "3"

    /** A valid `tts.voice` / `TtsRequest.voice`: a VOICEVOX style id in decimal digits (SCHEMA.md 6.1 `string (voice)`). */
    public const val VOICE_PATTERN: String = "^[0-9]{1,9}$"

    /** Longest speech text [WordAudioResolver] returns, in UTF-16 code units; a longer one gives null. */
    public const val MAX_TEXT_LENGTH: Int = 100

    /** The note field that may hold the note's own word audio. */
    public const val NOTE_FIELD: String = "audio"

    /** A sound reference in a field value; group 1 is the blob hash. Only this exact form counts (lowercase hex, 64 digits). */
    public const val SOUND_PATTERN: String = "\\[sound:([0-9a-f]{64})\\]"
}

/**
 * Decides the word audio of a note (ADR-047, C-29; SCHEMA.md 6.6). Pure: the same input gives the
 * same result on jvm, android and js, so the server, the phone's local API and the phone's
 * prefetch pass agree on which sound a card has without talking to each other. It never reads
 * settings, the clock or any table.
 *
 * **[resolve]**, first match wins:
 * 1. `noteType.baseType` is not vocab → null. (Phrase, sentence, kanji, grammar and custom notes
 *    have no generated audio in v1; their own `{{audio}}` renders as before.)
 * 2. `fields["audio"]` contains a match of [WordAudioRules.SOUND_PATTERN] → [WordAudio.Note] with
 *    the hash of the **first** match (document order). A non-empty `audio` field without such a
 *    match (`<br>`, `&nbsp;`, a bare file name, an upper-case hash) is not a sound and falls through.
 * 3. T = the *speech text* (below). T is null → null. Else [WordAudio.Tts] (T).
 *
 * **Speech text.** For each candidate in order, `reading` then `expression`:
 * 1. Take the *text of the field* in `kana` mode exactly as [CardTextRenderer] defines it (a
 *    non-blank `note.fields[key]` wins, else the [DictionaryData] fill; bracket furigana
 *    `食[た]べる` gives `たべる`; tags, entities, `[sound:…]` and images removed).
 * 2. Keep the first line (the [CardTextRenderer] line rule; empty lines are dropped first).
 * 3. Cut at the first occurrence of any of `、` `,` `，` `;` `；` `/` `／` (U+3001, U+002C,
 *    U+FF0C, U+003B, U+FF1B, U+002F, U+FF0F), keeping what is before it: a field listing
 *    several readings speaks the first.
 * 4. Remove every U+0020, U+0009, U+00A0 and U+3000.
 * 5. Empty → try the next candidate. Longer than [WordAudioRules.MAX_TEXT_LENGTH] UTF-16 code
 *    units → the speech text is null (no further candidate is tried). Otherwise this is T.
 *
 * Both candidates empty → null. Nothing else is changed: no Unicode normalisation, no
 * hiragana/katakana folding, no case folding (so `たべる` and `タベル` are different texts and
 * different pairs).
 *
 * **Worked examples** (vocab note type; `D` = dictionary data of 食べる / たべる):
 *
 * | fields | dictionary | result |
 * |---|---|---|
 * | `{}` (deck-materialized) | D | `Tts("たべる")` |
 * | `{audio: "[sound:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08]"}` | D | `Note("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")` |
 * | `{audio: "<br>"}` | D | `Tts("たべる")` |
 * | `{audio: "[sound:AAAA]"}` | D | `Tts("たべる")` (not a valid reference) |
 * | `{audio: "[sound:<h1>] [sound:<h2>]"}` (two valid hashes) | D | `Note(h1)` |
 * | `{reading: "くう"}` | D | `Tts("くう")` (the user's reading wins) |
 * | `{reading: "あした、あす"}` | null | `Tts("あした")` |
 * | `{reading: "た べ る"}` | null | `Tts("たべる")` |
 * | `{expression: "食[た]べる"}` | null | `Tts("たべる")` (reading empty, expression in kana mode) |
 * | `{expression: "食べる"}` | null | `Tts("食べる")` (the engine reads the kanji; best effort) |
 * | `{}` | null | null |
 * | `{reading: "<br>"}` | null | null |
 * | `{reading: "あ" × 101}` | D | null (too long; the expression is not tried) |
 * | any fields, base type kanji / phrase / sentence / grammar / custom | any | null |
 *
 * **[effectiveVoice]**: [stored] when it matches [WordAudioRules.VOICE_PATTERN], else
 * [WordAudioRules.DEFAULT_VOICE]. Hosts pass the JSON string value of the setting `tts.voice`, or
 * null when the row is missing, JSON null or not a string. Examples: `"8"` → `"8"`; `"08"` →
 * `"08"`; null, `""`, `"ずんだもん"`, `"3 "` → `"3"`.
 */
public interface WordAudioResolver {
    /** The word audio of the note, or null when it has none. Never throws for any note content. */
    public fun resolve(input: WordAudioInput): WordAudio?

    /** The voice paired with a [WordAudio.Tts] text. Never throws. */
    public fun effectiveVoice(stored: String?): String
}
