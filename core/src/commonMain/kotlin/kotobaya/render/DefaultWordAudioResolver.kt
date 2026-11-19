package kotobaya.render

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.render.WordAudio
import kotobaya.contracts.render.WordAudioInput
import kotobaya.contracts.render.WordAudioResolver
import kotobaya.contracts.render.WordAudioRules

/**
 * The one [WordAudioResolver] (ADR-047, C-29; SCHEMA.md 6.6). Common code: the speech text is read
 * with the same field resolution and plain-text rules as [DefaultCardTextRenderer], so the server,
 * the phone and the web agree on the pair for every note.
 */
public object DefaultWordAudioResolver : WordAudioResolver {
    private val sound = Regex(WordAudioRules.SOUND_PATTERN)
    private val voice = Regex(WordAudioRules.VOICE_PATTERN)
    private val separators = charArrayOf('、', ',', '，', ';', '；', '/', '／')
    private val spaces = Regex("[\\u0020\\u0009\\u00A0\\u3000]")
    private val candidates = listOf("reading", "expression")

    override fun resolve(input: WordAudioInput): WordAudio? {
        if (input.noteType.baseType != NoteBaseType.VOCAB) return null
        noteSound(input.fields)?.let { return WordAudio.Note(it) }
        return speechText(input)?.let { WordAudio.Tts(it) }
    }

    override fun effectiveVoice(stored: String?): String =
        if (stored != null && voice.matches(stored)) stored else WordAudioRules.DEFAULT_VOICE

    /** The hash of the first `[sound:<sha256>]` of the note's `audio` field, or null (also used by the renderer's block W). */
    internal fun noteSound(fields: Map<String, String>): String? =
        sound.find(fields[WordAudioRules.NOTE_FIELD] ?: "")?.groupValues?.get(1)

    private fun speechText(input: WordAudioInput): String? {
        for (key in candidates) {
            val line = DefaultCardTextRenderer.fieldLines(input.fields, input.dictionary, null, NoteBaseType.VOCAB, key, TextMode.KANA)
                .firstOrNull() ?: continue
            val cut = line.indexOfAny(separators).let { if (it < 0) line else line.substring(0, it) }
            val text = spaces.replace(cut, "")
            if (text.isEmpty()) continue
            return if (text.length > WordAudioRules.MAX_TEXT_LENGTH) null else text
        }
        return null
    }
}
