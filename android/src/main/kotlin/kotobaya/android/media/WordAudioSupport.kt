package kotobaya.android.media

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.dict.DictEntryDetail
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.FuriganaSegment
import kotobaya.contracts.render.WordAudio
import kotobaya.contracts.render.WordAudioInput
import kotobaya.contracts.sync.JsonString
import kotobaya.render.DefaultWordAudioResolver
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables

/** One row of the device table `tts_audio` (Local.sq): the audio blob of a (voice, text) pair. */
data class TtsAudioRow(val blobHash: String, val durationMs: Long, val checkedAt: Long)

/**
 * The device table `tts_audio` (ADR-047, SCHEMA.md 6.6): pairs resolved with the server. Derived data, no op, safe to
 * empty. Raw SQL over the contract table, like [BlobStore].
 */
class TtsAudioTable(private val driver: SqlDriver) {
    fun get(voice: String, text: String): TtsAudioRow? = driver.executeQuery(
        null,
        "SELECT blob_hash, duration_ms, checked_at FROM tts_audio WHERE voice = ? AND text = ?",
        { c -> QueryResult.Value(if (c.next().value) TtsAudioRow(c.getString(0)!!, c.getLong(1)!!, c.getLong(2)!!) else null) },
        2,
    ) {
        bindString(0, voice)
        bindString(1, text)
    }.value

    fun upsert(voice: String, text: String, blobHash: String, durationMs: Long, checkedAt: Long) {
        driver.execute(
            null,
            "INSERT INTO tts_audio (voice, text, blob_hash, duration_ms, checked_at) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(voice, text) DO UPDATE SET blob_hash = excluded.blob_hash, " +
                "duration_ms = excluded.duration_ms, checked_at = excluded.checked_at",
            5,
        ) {
            bindString(0, voice)
            bindString(1, text)
            bindString(2, blobHash)
            bindLong(3, durationMs)
            bindLong(4, checkedAt)
        }
    }
}

/** What `QueueItem.word_audio.blob_hash` is for a `tts` pair on this device; null = no speaker button. */
fun interface WordAudioLookup {
    fun blobHash(voice: String, text: String): String?
}

/**
 * SCHEMA.md 6.6 "Device" rule 6: a pair is generated here iff its local row exists and the bytes can be had: the blob is
 * cached, or the device is online (the blob intercept downloads it).
 */
class DeviceWordAudioLookup(
    private val table: TtsAudioTable,
    private val blobs: BlobStore,
    private val isOnline: () -> Boolean,
) : WordAudioLookup {
    override fun blobHash(voice: String, text: String): String? {
        val row = table.get(voice, text) ?: return null
        return row.blobHash.takeIf { blobs.isCached(it) || isOnline() }
    }
}

/** The core word-audio rule applied to replica rows (shared by the local queue and the prefetch pass). */
internal object WordAudios {
    private const val VOICE_KEY = "tts.voice"

    /** `effectiveVoice` of the replica's `tts.voice` setting. */
    fun voice(t: LearningTables): String {
        val stored = (t.get(LearningTable.SETTINGS, listOf(JsonString(VOICE_KEY)))?.get("value") as? JsonString)?.value
        return DefaultWordAudioResolver.effectiveVoice(stored)
    }

    /** The word audio of a note, or null (never throws). */
    fun resolve(info: NoteTypeInfo, fields: Map<String, String>, dictionary: DictionaryData?): WordAudio? =
        if (info.baseType != NoteBaseType.VOCAB) null else runCatching { DefaultWordAudioResolver.resolve(WordAudioInput(info, fields, dictionary)) }.getOrNull()

    /** Headword and reading of an entry as renderer data (the resolver reads nothing else). */
    fun dictionary(e: DictEntryDetail): DictionaryData = DictionaryData(
        expression = e.headword,
        reading = e.headwordReading,
        furigana = listOf(FuriganaSegment(e.headword, e.headwordReading.takeUnless { it == e.headword })),
        glosses = emptyList(),
        pitchDrops = emptyList(),
    )
}
