package kotobaya.tts

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.notes.NotesModule
import kotobaya.review.ReviewModule
import kotobaya.settings.SettingsModule
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SRV-29 (ADR-047, C-29; SCHEMA.md 6.6 "Server"): `resolveSpeech` states and scope, `QueueItem.word_audio` with the
 * urgent enqueue, the backlog limits (300 / 340), the sweep and `synthesizeSpeech`'s default voice.
 */
class WordAudioApiTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache())

    private val notes by lazy { NotesModule(db, sync.opApply) }
    private val review by lazy { ReviewModule(db, sync.opApply) }
    private val settings by lazy { SettingsModule(db, sync.opApply) }
    private val tts by lazy { TtsModule(db) }
    private val vocab = "00000000-0000-7000-8000-000000000101"
    private val phrase = "00000000-0000-7000-8000-000000000103"

    private fun ttsApp(block: suspend ApplicationTestBuilder.() -> Unit) =
        app(extraHandlers = notes.handlers + review.handlers + settings.handlers + tts.handlers, block = block)

    private fun writer() = mint(TokenKind.PERSONAL, listOf("read", "write", "review")).second

    private suspend fun ApplicationTestBuilder.note(t: String, type: String, fields: Map<String, String>): JsonNode {
        val r = client.call(HttpMethod.Post, "/notes", bearer = t, body = mapOf("note_type_id" to type, "fields" to fields))
        assertEquals(201, r.status.value, r.json().toString())
        return r.json()
    }

    private suspend fun ApplicationTestBuilder.resolveCall(t: String, body: Any): HttpResponse =
        client.call(HttpMethod.Post, "/tts/resolve", bearer = t, body = body)

    private suspend fun ApplicationTestBuilder.resolve(t: String, body: Any): List<JsonNode> {
        val r = resolveCall(t, body)
        assertEquals(200, r.status.value, r.json().toString())
        return r.json()["items"].toList()
    }

    private fun hash(c: Char) = c.toString().repeat(64)

    /** Stores a generated pair: the blob row and the `tts_audio` row (as the worker would). */
    private fun generate(voice: String, text: String, blob: String, durationMs: Int = 640, blobRow: Boolean = true) = db.txBlocking {
        if (blobRow) {
            it.execute("insert into app.blob (hash, size_bytes, content_type, kind, origin) values (?, 10, 'audio/ogg', 'audio', 'tts') on conflict do nothing", blob)
        }
        it.execute("insert into app.tts_audio (voice, text, blob_hash, duration_ms) values (?, ?, ?, ?)", voice, text, blob, durationMs)
    }

    private fun ttsJobs(): Long = count("select count(*) from app.job where kind = 'tts'")

    private fun activeJobs(): Long = count("select count(*) from app.job where kind = 'tts' and status in ('queued', 'running')")

    private fun jobOf(voice: String, text: String): Pair<String, Instant>? = db.txBlocking {
        it.fetchOptional(
            "select status, run_after from app.job where kind = 'tts' and dedupe_key = ? order by created_at desc limit 1", ttsDedupeKey(voice, text),
        ).map { r -> r.get(0, String::class.java) to r.get(1, java.time.OffsetDateTime::class.java).toInstant() }.orElse(null)
    }

    /** Fills the backlog with [n] queued `tts` jobs of unrelated texts. */
    private fun fillBacklog(n: Int) = db.txBlocking {
        it.execute(
            "insert into app.job (id, kind, payload, dedupe_key) " +
                "select gen_random_uuid(), 'tts', jsonb_build_object('text', 'filler' || g, 'voice', '3'), 'filler:' || g from generate_series(1, ?) g",
            n,
        )
    }

    private fun failedJob(voice: String, text: String, secondsAgo: Int) = db.txBlocking {
        it.execute(
            "insert into app.job (id, kind, status, payload, dedupe_key, attempts, created_at, finished_at, error) values " +
                "(?, 'tts', 'failed', jsonb_build_object('text', ?::text, 'voice', ?::text), ?, 3, now() - make_interval(secs => ?), now() - make_interval(secs => ?), 'voicevox down')",
            UUID.randomUUID(), text, voice, ttsDedupeKey(voice, text), secondsAgo + 5, secondsAgo,
        )
    }

    // ---------------------------------------------------------------- resolveSpeech

    @Test
    fun resolveSpeechAnswersReadyPendingFailedAndNone() = ttsApp {
        val t = writer()
        generate("3", "たべる", hash('a'))
        failedJob("8", "はし", secondsAgo = 100)
        val own = note(t, vocab, mapOf("expression" to "猫", "audio" to "[sound:${hash('b')}]"))
        db.txBlocking { it.execute("insert into app.blob (hash, size_bytes, content_type, kind, origin) values (?, 10, 'audio/ogg', 'audio', 'upload')", hash('b')) }
        val lost = note(t, vocab, mapOf("expression" to "犬", "audio" to "[sound:${hash('c')}]"))
        val plain = note(t, vocab, mapOf("expression" to "食べる", "reading" to "たべる"))
        val phraseNote = note(t, phrase, mapOf("expression" to "おはよう"))
        val deleted = note(t, vocab, mapOf("expression" to "消える", "reading" to "きえる"))
        assertEquals(204, client.call(HttpMethod.Delete, "/notes/${deleted["id"].asText()}", bearer = t).status.value)
        val unknown = "01931f40-1a2b-7c3d-8e4f-000000000a99"

        val out = resolve(
            t,
            mapOf(
                "items" to listOf(
                    mapOf("text" to "たべる"),
                    mapOf("text" to "がっこう"),
                    mapOf("text" to "はし", "voice" to "8"),
                    mapOf("note_id" to own["id"].asText()),
                    mapOf("note_id" to lost["id"].asText()),
                    mapOf("note_id" to plain["id"].asText()),
                    mapOf("note_id" to phraseNote["id"].asText()),
                    mapOf("note_id" to deleted["id"].asText()),
                    mapOf("note_id" to unknown),
                    mapOf("text" to "がっこう", "voice" to null),
                ),
            ),
        )
        fun row(i: Int) = listOf("status", "source", "text", "voice", "blob_hash", "duration_ms").map { k -> out[i][k].takeUnless { it.isNull }?.asText() }
        assertEquals(listOf("ready", "tts", "たべる", "3", hash('a'), "640"), row(0))
        assertTrue(out[0]["retry_after_ms"].isNull)
        assertEquals(listOf("pending", "tts", "がっこう", "3", null, null), row(1))
        assertEquals(1000, out[1]["retry_after_ms"].asInt())
        assertEquals(listOf("failed", "tts", "はし", "8", null, null), row(2))
        val left = out[2]["retry_after_ms"].asLong()
        assertTrue(left in 400_000..500_000, "time left of the back-off: $left")
        assertEquals(listOf("ready", "note", null, null, hash('b'), null), row(3))
        assertEquals(listOf("failed", "note", null, null, null, null), row(4))
        assertEquals(600_000, out[4]["retry_after_ms"].asInt())
        assertEquals(listOf("ready", "tts", "たべる", "3", hash('a'), "640"), row(5), "a note resolves to the pair of its reading")
        for (i in 6..8) assertEquals(listOf("none", null, null, null, null, null), row(i), "item $i")
        assertEquals(row(1), row(9), "duplicates are answered alike")

        // One job, for the one pending pair; none for the failed (backing off) pair or for the notes' own sounds.
        assertEquals("queued", jobOf("3", "がっこう")?.first)
        assertEquals(2L, ttsJobs(), "the failed job plus the new one")
        val again = resolve(t, mapOf("items" to listOf(mapOf("text" to "がっこう"))))
        assertEquals("pending", again[0]["status"].asText())
        assertEquals(2L, ttsJobs(), "a repeated call never makes a second job for a pair")

        // Past the back-off the pair is enqueued again.
        failedJob("9", "あめ", secondsAgo = 700)
        assertEquals("pending", resolve(t, mapOf("items" to listOf(mapOf("text" to "あめ", "voice" to "9"))))[0]["status"].asText())
        assertEquals("queued", jobOf("9", "あめ")?.first)
    }

    @Test
    fun aTtsAudioRowWithoutItsBlobIsNotGeneratedAndIsDeleted() = ttsApp {
        val t = writer()
        generate("3", "たべる", hash('d'), blobRow = false)
        val out = resolve(t, mapOf("items" to listOf(mapOf("text" to "たべる"))))
        assertEquals("pending", out[0]["status"].asText())
        assertEquals(0L, count("select count(*) from app.tts_audio"))
    }

    @Test
    fun resolveSpeechNeedsWriteForEveryRequestShape() = ttsApp {
        val t = writer()
        val n = note(t, vocab, mapOf("expression" to "食べる", "reading" to "たべる"))
        val readOnly = mint(TokenKind.PERSONAL, listOf("read", "review")).second
        assertProblem(resolveCall(readOnly, mapOf("items" to listOf(mapOf("text" to "たべる")))), 403, "insufficient_scope")
        assertProblem(resolveCall(readOnly, mapOf("items" to listOf(mapOf("note_id" to n["id"].asText())))), 403, "insufficient_scope")
        assertProblem(resolveCall(readOnly, mapOf("urgent" to true, "items" to listOf(mapOf("text" to "たべる")))), 403, "insufficient_scope")
        assertEquals(0L, ttsJobs(), "a refused request enqueues nothing")
        val ok = resolve(t, mapOf("items" to listOf(mapOf("note_id" to n["id"].asText()))))
        assertEquals("pending", ok[0]["status"].asText())
        assertEquals(1L, ttsJobs())
    }

    @Test
    fun invalidRequestsAre422AndEnqueueNothing() = ttsApp {
        val t = writer()
        val id = "01931f40-1a2b-7c3d-8e4f-000000000a01"
        fun ptr(r: HttpResponse) = r.json()["errors"].map { it["pointer"].asText() }
        suspend fun bad(body: Any, pointer: String) {
            val r = resolveCall(t, body)
            assertEquals(422, r.status.value, r.json().toString())
            assertTrue(pointer in ptr(r), "${ptr(r)}")
        }
        bad(mapOf("items" to listOf(mapOf("text" to "あ"), mapOf("text" to "い", "note_id" to id))), "/items/1")
        bad(mapOf("items" to listOf(mapOf("text" to "あ"), mapOf<String, Any>())), "/items/1")
        bad(mapOf("items" to listOf(mapOf("note_id" to id, "voice" to "3"))), "/items/0")
        bad(mapOf("items" to emptyList<Any>()), "/items")
        bad(mapOf("items" to (1..201).map { mapOf("text" to "t$it") }), "/items")
        bad(mapOf("urgent" to true, "items" to (1..21).map { mapOf("text" to "t$it") }), "/urgent")
        bad(mapOf("items" to listOf(mapOf("text" to "あ", "voice" to "ずんだもん"))), "/items/0/voice")
        bad(mapOf("items" to listOf(mapOf("text" to ""))), "/items/0/text")
        assertEquals(0L, ttsJobs())
        assertEquals(200, resolveCall(t, mapOf("urgent" to true, "items" to (1..20).map { mapOf("text" to "t$it") })).status.value)
    }

    // ---------------------------------------------------------------- backlog limits

    @Test
    fun nonUrgentStopsAt300AndUrgentAt340() = ttsApp {
        val t = writer()
        fillBacklog(299)
        val out = resolve(t, mapOf("items" to listOf(mapOf("text" to "いち"), mapOf("text" to "に"), mapOf("text" to "さん"))))
        assertEquals(listOf(1000, 60000, 60000), out.map { it["retry_after_ms"].asInt() }, "a prefix of the missing pairs is enqueued")
        assertTrue(out.all { it["status"].asText() == "pending" })
        assertEquals(300L, activeJobs())
        assertEquals(null, jobOf("3", "に"))

        val urgent = resolve(t, mapOf("urgent" to true, "items" to listOf(mapOf("text" to "に"))))
        assertEquals(1000, urgent[0]["retry_after_ms"].asInt(), "urgent may use the reserve")
        val (status, runAfter) = checkNotNull(jobOf("3", "に"))
        assertEquals("queued", status)
        assertTrue(runAfter.isBefore(Instant.now().minusSeconds(23 * 3600)), "urgent = run_after now - 1 day: $runAfter")
    }

    @Test
    fun at340QueuedJobsNothingIsEnqueuedOrReprioritised() = ttsApp {
        val t = writer()
        resolve(t, mapOf("items" to listOf(mapOf("text" to "きた"))))
        val before = checkNotNull(jobOf("3", "きた")).second
        fillBacklog(339)
        assertEquals(340L, activeJobs())

        val out = resolve(t, mapOf("urgent" to true, "items" to listOf(mapOf("text" to "みなみ"), mapOf("text" to "きた"))))
        assertEquals(listOf("pending", "pending"), out.map { it["status"].asText() })
        assertEquals(60000, out[0]["retry_after_ms"].asInt())
        assertEquals(null, jobOf("3", "みなみ"), "no urgent enqueue at 340")
        assertEquals(before, jobOf("3", "きた")?.second, "no re-prioritising at 340")

        // The queue's own urgent enqueue stops at 340 too, and the request still succeeds.
        note(t, vocab, mapOf("expression" to "ひがし", "reading" to "ひがし"))
        val q = client.call(HttpMethod.Get, "/review/queue", bearer = t)
        assertEquals(200, q.status.value)
        assertTrue(q.json()["items"].any { it["word_audio"]["text"].asText() == "ひがし" })
        assertEquals(null, jobOf("3", "ひがし"))
        assertEquals(340L, activeJobs())
    }

    // ---------------------------------------------------------------- queue and drill items

    @Test
    fun queueAndDrillItemsCarryWordAudioAndQueueUrgentJobs() = ttsApp {
        val t = writer()
        // Kana expressions: no kanji note is materialized ahead of them (ADR-040), so every note is served.
        val plain = note(t, vocab, mapOf("expression" to "たべる", "reading" to "たべる"))
        note(t, vocab, mapOf("expression" to "ねこ", "audio" to "<br>[sound:${hash('b')}]"))
        note(t, phrase, mapOf("expression" to "おはよう"))
        failedJob("3", "ねこ", secondsAgo = 10) // unrelated: ねこ has its own sound

        val first = client.call(HttpMethod.Get, "/review/queue", bearer = t).json()["items"]
        val byExpr = first.groupBy { it["card"]["note"]["fields"]["expression"].asText() }
        val eat = byExpr.getValue("たべる").first()["word_audio"]
        assertEquals("tts", eat["source"].asText())
        assertTrue(eat["blob_hash"].isNull)
        assertEquals("たべる", eat["text"].asText())
        assertEquals("3", eat["voice"].asText())
        val cat = byExpr.getValue("ねこ").first()["word_audio"]
        assertEquals(listOf("note", hash('b')), listOf(cat["source"].asText(), cat["blob_hash"].asText()))
        assertTrue(cat["text"].isNull && cat["voice"].isNull)
        assertTrue(byExpr.getValue("おはよう").all { it["word_audio"].isNull }, "phrase notes have no word audio")

        val (status, runAfter) = checkNotNull(jobOf("3", "たべる")) { "the served pair is queued" }
        assertEquals("queued", status)
        assertTrue(runAfter.isBefore(Instant.now().minusSeconds(23 * 3600)), "served pairs are urgent")
        assertEquals(1L, activeJobs(), "one job per pair, none for a note's own sound")

        generate("3", "たべる", hash('e'))
        val second = client.call(HttpMethod.Get, "/review/queue", bearer = t).json()["items"]
        val eat2 = second.first { it["card"]["note"]["id"] == plain["id"] }["word_audio"]
        assertEquals(hash('e'), eat2["blob_hash"].asText())

        // A changed voice asks for the other pair; the old one stays generated.
        assertEquals(200, client.call(HttpMethod.Patch, "/settings", bearer = t, body = """{"values":{"tts.voice":"8"}}""").status.value)
        val cardId = plain["cards"][0]["id"].asText()
        val drill = client.call(HttpMethod.Post, "/drill/session", bearer = t, body = mapOf("source" to "cards", "card_ids" to listOf(cardId)))
        assertEquals(200, drill.status.value)
        val d = drill.json()["items"][0]["word_audio"]
        assertEquals(listOf("tts", "たべる", "8"), listOf(d["source"].asText(), d["text"].asText(), d["voice"].asText()))
        assertTrue(d["blob_hash"].isNull)
        assertEquals("queued", jobOf("8", "たべる")?.first, "drill sessions queue their pairs too")
    }

    @Test
    fun aReadOnlyQueueFetchStillQueuesTheSpeechOfItsCards() = ttsApp {
        val t = writer()
        note(t, vocab, mapOf("expression" to "みず", "reading" to "みず"))
        // Queue as a read-only caller; the note's cards already exist, so nothing needs materializing.
        val reader = mint(TokenKind.PERSONAL, listOf("read")).second
        val q = client.call(HttpMethod.Get, "/review/queue", bearer = reader)
        assertEquals(200, q.status.value)
        assertEquals("queued", jobOf("3", "みず")?.first)
    }

    // ---------------------------------------------------------------- the sweep

    private fun makeDue(noteId: String, dueInDays: Double) = db.txBlocking {
        it.execute(
            "update app.card set state = 'review', due_at = ?::timestamptz, stability = 5, difficulty = 5, interval_days = 3, reps = 1, last_review_at = ?::timestamptz where note_id = ?::uuid",
            java.time.OffsetDateTime.ofInstant(Instant.now().plusMillis((dueInDays * 86_400_000).toLong()), java.time.ZoneOffset.UTC),
            java.time.OffsetDateTime.ofInstant(Instant.now().minusSeconds(86_400 * 3), java.time.ZoneOffset.UTC),
            noteId,
        )
    }

    @Test
    fun theSweepEnqueuesPairsOfCardsDueInTheWindowInDueOrderUpTo300() = ttsApp {
        val t = writer()
        val soon = note(t, vocab, mapOf("expression" to "朝", "reading" to "あさ"))
        val later = note(t, vocab, mapOf("expression" to "夜", "reading" to "よる"))
        val outside = note(t, vocab, mapOf("expression" to "春", "reading" to "はる"))
        val generated = note(t, vocab, mapOf("expression" to "夏", "reading" to "なつ"))
        val backingOff = note(t, vocab, mapOf("expression" to "秋", "reading" to "あき"))
        val suspended = note(t, vocab, mapOf("expression" to "冬", "reading" to "ふゆ"))
        note(t, vocab, mapOf("expression" to "新", "reading" to "しん")) // new cards only: part (b), not the sweep's
        makeDue(soon["id"].asText(), 1.0)
        makeDue(later["id"].asText(), 2.0)
        makeDue(outside["id"].asText(), 8.0)
        makeDue(generated["id"].asText(), 0.5)
        makeDue(backingOff["id"].asText(), 0.5)
        makeDue(suspended["id"].asText(), 0.5)
        db.txBlocking { it.execute("update app.card set suspended = true where note_id = ?::uuid", suspended["id"].asText()) }
        generate("3", "なつ", hash('f'))
        failedJob("3", "あき", secondsAgo = 30)

        fillBacklog(299)
        val sweep = WordAudioSweep(db)
        assertEquals(1, sweep.runOnce(), "stops when 300 tts jobs are queued or running")
        assertEquals("queued", jobOf("3", "あさ")?.first, "earliest due first")
        assertEquals(null, jobOf("3", "よる"))
        assertTrue(jobOf("3", "あさ")!!.second.isAfter(Instant.now().minusSeconds(3600)), "the sweep is not urgent")

        db.txBlocking { it.execute("delete from app.job where dedupe_key like 'filler:%'") }
        assertEquals(1, sweep.runOnce())
        assertEquals("queued", jobOf("3", "よる")?.first)
        for (text in listOf("はる", "ふゆ", "しん")) assertEquals(null, jobOf("3", text), text)
        assertEquals("failed", jobOf("3", "あき")?.first, "backing off: no new job")
        assertEquals(null, jobOf("3", "なつ"), "generated: no job")
        assertEquals(0, sweep.runOnce(), "pairs with a queued job are skipped")

        // audio.prefetch_days widens the window (0-365).
        assertEquals(200, client.call(HttpMethod.Patch, "/settings", bearer = t, body = """{"values":{"audio.prefetch_days":30}}""").status.value)
        assertEquals(1, sweep.runOnce())
        assertEquals("queued", jobOf("3", "はる")?.first)
    }

    // ---------------------------------------------------------------- synthesizeSpeech

    @Test
    fun synthesizeSpeechDefaultsToTheEffectiveVoice() = ttsApp {
        val t = writer()
        suspend fun voiceOf(text: String): String {
            val r = client.call(HttpMethod.Post, "/tts", bearer = t, body = mapOf("text" to text))
            assertEquals(202, r.status.value, r.json().toString())
            return r.json()["payload"]["voice"].asText()
        }
        assertEquals("3", voiceOf("いち"))
        // An invalid stored value (pushed ops are not type-checked) means the default.
        db.txBlocking {
            it.execute(
                "insert into app.settings (key, value, hlc, updated_at) values ('tts.voice', '\"ずんだもん\"', '000000000001-0000-00000000-0000-7000-8000-000000000001', now())",
            )
        }
        assertEquals("3", voiceOf("に"))
        assertEquals(200, client.call(HttpMethod.Patch, "/settings", bearer = t, body = """{"values":{"tts.voice":"08"}}""").status.value)
        assertEquals("08", voiceOf("さん"))
    }
}
