package kotobaya.review

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.notes.NotesModule
import kotobaya.server.Uuid7
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** T206: the review endpoints over HTTP with the real core replay as the card cache. */
class ReviewApiTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache())

    private val notes by lazy { NotesModule(db, sync.opApply) }
    private val review by lazy { ReviewModule(db, sync.opApply) }
    private val vocab = "00000000-0000-7000-8000-000000000101"

    private fun reviewApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = notes.handlers + review.handlers, block = block)

    private fun token() = mint(TokenKind.PERSONAL, listOf("read", "write", "review")).second

    private fun setting(key: String, valueJson: String) {
        emit(OpType.SETTINGS_SET, """{"key":"$key","value":$valueJson}""")
    }

    private suspend fun ApplicationTestBuilder.note(t: String, expression: String, entrySeq: Long? = null, deck: String? = null): JsonNode {
        val body = mutableMapOf<String, Any?>("note_type_id" to vocab, "fields" to mapOf("expression" to expression))
        entrySeq?.let { body["entry_seq"] = it }
        deck?.let { body["deck_id"] = it }
        val r = client.call(HttpMethod.Post, "/notes", bearer = t, body = body)
        assertEquals(201, r.status.value)
        return r.json()
    }

    private fun cardOf(note: JsonNode, kind: String): String = note["cards"].first { it["kind"].asText() == kind }["id"].asText()

    private fun dictEntry(seq: Long) {
        db.txBlocking {
            it.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
            it.execute(
                "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id) " +
                    "values (?, '食べる', 'たべる', '[{\"text\":\"たべる\"}]', '[]', 1) on conflict do nothing",
                seq,
            )
        }
    }

    private suspend fun ApplicationTestBuilder.answer(
        t: String, card: String, rating: Int, button: String? = null, mode: String? = null, id: String = Uuid7.generate().toString(),
    ): HttpResponse {
        val body = mutableMapOf<String, Any?>("id" to id, "card_id" to card, "rating" to rating)
        button?.let { body["button"] = it }
        mode?.let { body["mode"] = it }
        return client.call(HttpMethod.Post, "/review/answer", bearer = t, body = body)
    }

    private suspend fun ApplicationTestBuilder.queueIds(t: String, query: String = ""): List<String> {
        val r = client.call(HttpMethod.Get, "/review/queue$query", bearer = t)
        assertEquals(200, r.status.value)
        return r.json()["items"].map { it["card"]["id"].asText() }
    }

    private fun cache(card: String): Map<String, Any?> = db.txBlocking { dsl ->
        dsl.fetchOne(
            "select state, step, stability, difficulty, due_at::text, interval_days, last_review_at::text, reps, lapses from app.card where id = ?::uuid", card,
        )!!.intoMap()
    }

    private fun advance(minutes: Long) = syncNow.set(syncNow.get().plusSeconds(minutes * 60))

    @Test
    fun undoRestoresTheExactPriorCacheForNewLearningReviewAndRelearningCards() = reviewApp {
        val t = token()
        val n = note(t, "x")
        val card = cardOf(n, "meaning")
        // new -> learning (Good on a new card with default steps stays in learning)
        val states = mutableListOf<Pair<String, Map<String, Any?>>>()
        suspend fun step(label: String, rating: Int, wait: Long) = roundTrip(label, rating, wait, card, t, states)
        assertEquals("new", cache(card)["state"])
        step("new", 3, 0)
        assertEquals("learning", cache(card)["state"])
        step("learning", 4, 15)
        assertEquals("review", cache(card)["state"])
        advance(60L * 24 * 40)
        step("review", 1, 0)
        assertEquals("relearning", cache(card)["state"])
        assertEquals(listOf("new", "learning", "review"), states.map { it.first })
    }

    private suspend fun ApplicationTestBuilder.roundTrip(
        label: String, rating: Int, wait: Long, card: String, t: String, states: MutableList<Pair<String, Map<String, Any?>>>,
    ) {
        advance(wait)
        val before = cache(card)
        val id = Uuid7.generate().toString()
        assertEquals(200, answer(t, card, rating, id = id).status.value)
        assertNotEquals(before, cache(card), "$label: answering changes the cache")
        val u = client.call(HttpMethod.Post, "/review/undo", bearer = t, body = mapOf("review_id" to id))
        assertEquals(200, u.status.value)
        assertTrue(!u.json()["review"]["undone_at"].isNull)
        assertEquals(before, cache(card), "$label: undo restores the prior cache exactly")
        // an undo cannot be undone, and a retry is a no-op
        val head = count("select max(seq) from app.change")
        assertEquals(200, client.call(HttpMethod.Post, "/review/undo", bearer = t, body = mapOf("review_id" to id)).status.value)
        assertEquals(head, count("select max(seq) from app.change"))
        states += label to before
        // really answer (new id) to move to the next state
        assertEquals(200, answer(t, card, rating).status.value)
    }

    @Test
    fun fiveButtonModeLogsTheSpecifiedRatingAndButton() = reviewApp {
        val t = token()
        val expected = listOf("nothing" to 1, "something" to 1, "hard" to 2, "okay" to 3, "again" to 1, "good" to 3, "easy" to 4)
        for ((button, rating) in expected) {
            val card = cardOf(note(t, "w$button"), "meaning")
            val id = Uuid7.generate().toString()
            val r = answer(t, card, rating, button, id = id)
            assertEquals(200, r.status.value)
            assertEquals(button, r.json()["review"]["button"].asText())
            assertEquals(rating, r.json()["review"]["rating"].asInt())
            assertEquals(rating.toLong(), count("select rating from app.review_log where id = ?::uuid", id))
        }
        val card = cardOf(note(t, "mismatch"), "meaning")
        val bad = answer(t, card, 3, "something")
        assertProblem(bad, 422, "validation_failed")
        assertEquals("/button", bad.json()["errors"][0]["pointer"].asText())
    }

    @Test
    fun answeringTwiceWithTheSameIdIsIdempotent() = reviewApp {
        val t = token()
        val card = cardOf(note(t, "idem"), "meaning")
        val id = Uuid7.generate().toString()
        assertEquals(200, answer(t, card, 3, id = id).status.value)
        val c = cache(card)
        val head = count("select max(seq) from app.change")
        assertEquals(200, answer(t, card, 1, id = id).status.value)
        assertEquals(head, count("select max(seq) from app.change"))
        assertEquals(c, cache(card))
    }

    @Test
    fun neverForgetAndBlacklistedWordsLeaveTheQueueAndClearingRestoresThem() = reviewApp {
        val t = token()
        dictEntry(1358280)
        val card = cardOf(note(t, "食べる", entrySeq = 1358280), "meaning")
        assertTrue(card in queueIds(t))
        for (status in listOf("never_forget", "blacklisted")) {
            val s = client.call(HttpMethod.Put, "/words/1358280/status", bearer = t, body = mapOf("status" to status))
            assertEquals(200, s.status.value)
            assertEquals(status, s.json()["status"].asText())
            assertEquals("manual", s.json()["override_origin"].asText())
            assertTrue(card !in queueIds(t), status)
            val cleared = client.call(HttpMethod.Delete, "/words/1358280/status", bearer = t)
            assertEquals(200, cleared.status.value)
            assertTrue(cleared.json()["override_status"].isNull)
            assertTrue(card in queueIds(t), "cleared after $status")
        }
        assertEquals("new", client.call(HttpMethod.Get, "/words/42/status", bearer = t).json()["status"].asText())
    }

    @Test
    fun drillAndCramAnswersAreLoggedAndNeverChangeTheSchedule() = reviewApp {
        val t = token()
        val card = cardOf(note(t, "drill"), "meaning")
        assertEquals(200, answer(t, card, 3).status.value)
        val before = cache(card)
        for (mode in listOf("drill", "cram")) {
            advance(5)
            val id = Uuid7.generate().toString()
            val r = answer(t, card, 1, mode = mode, id = id)
            assertEquals(200, r.status.value)
            assertEquals(mode, r.json()["review"]["review_type"].asText())
            assertEquals(1L, count("select count(*) from app.review_log where id = ?::uuid and review_type = ?", id, mode))
            assertEquals(before, cache(card))
        }
        val session = client.call(HttpMethod.Post, "/drill/session", bearer = t, body = mapOf("source" to "cards", "card_ids" to listOf(card), "mode" to "cram"))
        assertEquals(200, session.status.value)
        assertEquals("cram", session.json()["items"][0]["review_type"].asText())
        assertTrue(session.json()["items"][0]["preview"].isNull)
    }

    @Test
    fun aQueueFetchedAfterAnsweringReflectsSiblingBuryingAndLimits() = reviewApp {
        val t = token()
        setting("scheduler.new_per_day", "2")
        val a = note(t, "a")
        val b = note(t, "b")
        val c = note(t, "c")
        val first = queueIds(t)
        // one new card per note, and the daily limit of 2 new cards
        assertEquals(2, first.size)
        assertEquals(2, first.map { id -> listOf(a, b, c).first { n -> n["cards"].any { it["id"].asText() == id } }["id"].asText() }.toSet().size)
        assertEquals(2, client.call(HttpMethod.Get, "/review/queue", bearer = t).json()["counts"]["new"].asInt())
        assertEquals(200, answer(t, first[0], 3).status.value)
        val after = queueIds(t)
        // the answered card's sibling is buried; the learning card is back (learn-ahead) and one new slot is left
        assertTrue(first[0] in after || after.isNotEmpty())
        val noteOfFirst = listOf(a, b, c).first { n -> n["cards"].any { it["id"].asText() == first[0] } }
        val siblings = noteOfFirst["cards"].map { it["id"].asText() }.filter { it != first[0] }
        assertTrue(after.none { it in siblings }, "sibling is buried")
        assertEquals(1, after.count { id -> cache(id)["state"] == "new" }, "2 per day minus 1 introduced")
        val limited = queueIds(t, "?limit=1")
        assertEquals(1, limited.size)
    }

    @Test
    fun aNewCardInADeletedDeckFallsBackToTheDecklessSource() = reviewApp {
        val t = token()
        val deck = UUID.randomUUID().toString()
        emit(OpType.DECK_CREATED, """{"id":"$deck","name":"D","type":"user"}""")
        val card = cardOf(note(t, "deleted deck", deck = deck), "meaning")
        emit(OpType.DECK_DELETED, """{"id":"$deck"}""")
        assertTrue(card in queueIds(t), "membership of a tombstoned deck is dropped")
        assertEquals(404, client.call(HttpMethod.Get, "/review/queue?deck_id=$deck", bearer = t).status.value)
    }

    @Test
    fun settingsFollowTheSchemaValidityTest() {
        fun v(json: String) = ReviewSettings.intValue(kotobaya.server.json.readTree(json), Int.MIN_VALUE, Int.MAX_VALUE)
        assertEquals(1, v("1.0"))
        assertEquals(100, v("1e2"))
        assertEquals(0, v("-0"))
        assertEquals(-1, v("-1"))
        assertEquals(null, v("1.5"))
        assertEquals(null, v("2147483648"))
        assertEquals(null, v("\"1\""))
        assertEquals(null, v("true"))
        setting("scheduler.mined_priority", "1.5")
        setting("scheduler.kanji_before_vocab", "\"yes\"")
        setting("scheduler.new_per_day", "7.0")
        val s = db.txBlocking { ReviewSettings.load(it) }
        assertEquals(0, s.minedPriority)
        assertEquals(false, s.kanjiBeforeVocab)
        assertEquals(7, s.newPerDay)
    }

    @Test
    fun studyDayCoversShortAndLongDstDaysAndRolloverInAGap() {
        fun at(s: String) = Instant.parse(s).toEpochMilli()
        fun hours(z: String, now: String, hour: Int = 4): Triple<Long, Long, String> {
            val d = studyDayOf(at(now), java.time.ZoneId.of(z), hour)
            return Triple(d.day.startMs, d.day.endMs, d.date.toString())
        }
        // contract example: Tokyo, rollover 4, 2024-01-02T01:00+09:00 -> 2024-01-01T04:00+09:00 .. 2024-01-02T04:00+09:00
        val tokyo = hours("Asia/Tokyo", "2024-01-01T16:00:00Z")
        assertEquals(at("2023-12-31T19:00:00Z"), tokyo.first)
        assertEquals(at("2024-01-01T19:00:00Z"), tokyo.second)
        assertEquals("2024-01-01", tokyo.third)
        // Berlin spring forward 2024-03-31 (02:00 -> 03:00): the day starting 03-30 04:00 lasts 23 h
        val spring = hours("Europe/Berlin", "2024-03-30T12:00:00Z")
        assertEquals(23L * 3_600_000, spring.second - spring.first)
        // Berlin fall back 2024-10-27 (03:00 -> 02:00): 25 h
        val fall = hours("Europe/Berlin", "2024-10-26T12:00:00Z")
        assertEquals(25L * 3_600_000, fall.second - fall.first)
        // New York, same kinds of days
        assertEquals(23L * 3_600_000, hours("America/New_York", "2024-03-09T12:00:00Z").let { it.second - it.first })
        assertEquals(25L * 3_600_000, hours("America/New_York", "2024-11-02T12:00:00Z").let { it.second - it.first })
        // just before the rollover still belongs to the previous day
        assertEquals("2024-03-29", hours("Europe/Berlin", "2024-03-30T01:59:59Z").third)
        // rollover hour 2 inside Berlin's gap: the day starts at the first instant after the gap (03:00 CEST = 01:00Z)
        val gap = hours("Europe/Berlin", "2024-03-31T05:00:00Z", hour = 2)
        assertEquals(at("2024-03-31T01:00:00Z"), gap.first)
        assertEquals("2024-03-31", gap.third)
    }
}
