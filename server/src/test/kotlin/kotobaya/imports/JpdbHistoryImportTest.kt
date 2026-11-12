package kotobaya.imports

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpMethod
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.review.ReviewModule
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import kotobaya.sync.merge.DuplicateMerge
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T207b: applying T207a's plan of fixtures/jpdb/reviews-sample.json as server ops.
 *
 * `reviews-sample.plan.json` next to this file is the worker's `ImportPlan.to_json()` for the sample (JMdict subset
 * of fixtures/dict) plus the worker's `job_result` against an empty database, generated with
 * `uv run --project worker python <script>` calling `plan_import` / `job_result` (see the T207b HANDOFF).
 */
class JpdbHistoryImportTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache(), merge = DuplicateMerge())

    private val sample: JsonNode by lazy {
        val dir = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "server/src/test/kotlin/kotobaya/imports") }.firstOrNull { it.isDirectory }
            ?: File(System.getProperty("user.dir"), "src/test/kotlin/kotobaya/imports")
        json.readTree(File(dir, "reviews-sample.plan.json"))
    }
    private val plan: ObjectNode get() = sample["plan"].deepCopy()
    private val importer by lazy { JpdbHistoryApply(sync.opApply, batchUnits = 2) }

    private fun run(p: JsonNode = plan, progress: ImportProgress = ImportProgress.None): ObjectNode = importer.apply(p, progress)

    private fun serverOps(type: OpType): Long =
        count("select count(*) from app.change where op_type = ? and device_id = ?::uuid", type.wire, SERVER)

    private fun liveNote(column: String, value: Any): String? = db.txBlocking { dsl ->
        dsl.fetchOptional("select id::text from app.note where $column = ? and deleted_at is null", value).map { it.get(0, String::class.java) }.orElse(null)
    }

    private fun card(noteId: String, kind: String): Map<String, Any?>? = db.txBlocking { dsl ->
        dsl.fetchOptional(
            "select id::text as id, suspended, state, due_at::text as due_at from app.card where note_id = ?::uuid and kind = ? and deleted_at is null",
            noteId, kind,
        ).map { it.intoMap() }.orElse(null)
    }

    private fun assertSums(r: JsonNode) = JpdbHistoryApply.checkSums(r)

    // ------------------------------------------------------------------ acceptance

    @Test
    fun sampleOpsMatchThePlanAndReplayGivesEveryReviewedCardADueDate() {
        val r = run()
        // Plan: 4 carded vocab groups + 2 kanji characters; 4 meaning + 2 kanji cards; 13 ratings; 1 known-only word.
        assertEquals(6, serverOps(OpType.NOTE_CREATED))
        assertEquals(6, serverOps(OpType.CARD_CREATED))
        assertEquals(13, serverOps(OpType.REVIEW_LOGGED))
        assertEquals(1, serverOps(OpType.WORD_STATUS_SET))
        assertEquals(0, serverOps(OpType.CARD_SUSPENDED), "suspension travels in card.created, never as card.suspended")
        assertEquals(13, count("select count(*) from app.review_log where import_source = 'jpdb' and utc_offset_min is null"))

        // The server's result equals the worker's prediction for an empty database, member for member.
        val expected = sample["expected_result_empty_db"] as ObjectNode
        expected.fieldNames().forEach { k -> assertEquals(expected[k], r[k], k) }
        assertEquals(0, r["reviews_id_collisions"].asInt())
        assertSums(r)

        // Every card with a planned review has a schedule; no vocab card other than `meaning` exists.
        val reviewed = count(
            "select count(distinct c.id) from app.card c join app.review_log l on l.card_id = c.id where c.due_at is null or c.state = 'new'",
        )
        assertEquals(0, reviewed)
        assertEquals(5, count("select count(distinct card_id) from app.review_log"), "寿司 has only an unknown event")
        assertEquals(0, count("select count(*) from app.card c join app.note n on n.id = c.note_id where n.type = 'vocab' and c.kind <> 'meaning'"))
        assertEquals(6, count("select count(*) from app.note where origin = 'jpdb_import'"))
    }

    @Test
    fun rerunningTheSameImportChangesNothing() {
        val first = run()
        val head = count("select max(seq) from app.change")
        val second = run()
        assertEquals(head, count("select max(seq) from app.change"), "a re-run emits no op")
        assertEquals(first["reviews_imported"], second["reviews_already_imported"])
        assertEquals(0, second["reviews_imported"].asInt())
        assertEquals(4, second["notes_existing"].asInt())
        assertEquals(0, second["notes_created"].asInt())
        assertEquals(0, second["cards_created"].asInt())
        assertEquals(2, second["kanji_notes_existing"].asInt())
        assertEquals(0, second["kanji_cards_created"].asInt())
        assertEquals(0, second["word_statuses_set"].asInt())
        assertEquals(0, second["reviews_id_collisions"].asInt())
        assertSums(second)
    }

    @Test
    fun writingCardsAreSuspendedAndNotQueuedAndAKnownOnlyWordHasStatusButNoCard() {
        syncNow.set(Instant.parse("2027-01-01T00:00:00Z")) // every imported card is due by then
        run()
        val gaku = assertNotNull(liveNote("kanji_literal", "学"))
        val writing = assertNotNull(card(gaku, "writing"))
        assertEquals(true, writing["suspended"])
        assertNull(card(gaku, "keyword"), "only the planned kind is created")
        val neko = assertNotNull(liveNote("kanji_literal", "猫"))
        val keyword = assertNotNull(card(neko, "keyword"))
        assertEquals(false, keyword["suspended"])

        val shizuka = 1381820L
        assertNull(liveNote("entry_seq", shizuka), "known-only vocab gets no note")
        val ws = db.txBlocking { it.fetchOne("select override_status, override_origin from app.word_status where entry_seq = ?", shizuka)!!.intoList() }
        assertEquals(listOf<Any?>("known", "jpdb_import"), ws)

        val review = ReviewModule(db, sync.opApply)
        app(extraHandlers = review.handlers) {
            val t = mint(TokenKind.PERSONAL, listOf("read", "review")).second
            val r = client.call(HttpMethod.Get, "/review/queue", bearer = t)
            assertEquals(200, r.status.value)
            val ids = r.json()["items"].map { it["card"]["id"].asText() }
            assertFalse(writing["id"] in ids, "a suspended writing card is never queued")
            assertTrue(keyword["id"] in ids, "the due keyword card is queued (the queue is not empty by accident)")
        }
    }

    @Test
    fun aKanjiNoteFromTheImportAndAnOfflineAddOfTheSameKanjiMergeToOneNote() {
        run()
        val imported = assertNotNull(liveNote("kanji_literal", "猫"))
        // A phone that was offline during the import adds 猫 too, then syncs.
        val phoneNote = Uuid7.generate().toString()
        val at = syncNow.get().toEpochMilli() + 60_000
        val push = pushAsOwner(op(OpType.NOTE_CREATED, A, at, """{"id":"$phoneNote","note_type_id":"${JpdbHistoryApply.KANJI_TYPE}","kanji_literal":"猫"}"""))
        assertAccepted(push)
        assertEquals(1, count("select count(*) from app.note where kanji_literal = '猫' and deleted_at is null"))
        assertEquals(imported, liveNote("kanji_literal", "猫"), "the import's note held the literal first and survives")
        assertEquals(imported, db.txBlocking { it.fetchValue("select merged_into_id::text from app.note where id = ?::uuid", phoneNote) as String? })
        assertEquals(1, count("select count(*) from app.change where op_type = 'note.merged'"))
    }

    @Test
    fun aKanjiAddedOfflineBeforeTheImportIsReusedNotDuplicated() {
        val phoneNote = Uuid7.generate().toString()
        assertAccepted(pushAsOwner(op(OpType.NOTE_CREATED, A, syncNow.get().toEpochMilli(), """{"id":"$phoneNote","note_type_id":"${JpdbHistoryApply.KANJI_TYPE}","kanji_literal":"猫"}""")))
        val r = run()
        assertEquals(1, r["kanji_notes_existing"].asInt())
        assertEquals(1, r["kanji_notes_created"].asInt())
        assertEquals(phoneNote, liveNote("kanji_literal", "猫"))
        assertNotNull(card(phoneNote, "keyword"))
        assertEquals(0, count("select count(*) from app.change where op_type = 'note.merged'"))
        assertSums(r)
    }

    @Test
    fun theReportListsEveryUnmappedItem() {
        val r = run()
        val unmapped = r["unmapped"] as ArrayNode
        assertEquals(plan["unmapped"], unmapped)
        assertEquals(1, unmapped.size())
        val u = unmapped[0]
        assertEquals(2147656918L, u["jpdb_vid"].asLong())
        assertEquals("山田太郎", u["spelling"].asText())
        assertEquals("やまだたろう", u["reading"].asText())
        assertEquals(3, u["review_count"].asInt())
        assertEquals(plan["kanji_unmapped"], r["kanji_unmapped"])
    }

    // ------------------------------------------------------------------ options and existing state

    @Test
    fun importReviewsFalseWritesOnlyWordStatuses() {
        val p = plan
        (p["options"] as ObjectNode).put("import_reviews", false)
        val r = run(p)
        assertEquals(0, serverOps(OpType.NOTE_CREATED) + serverOps(OpType.CARD_CREATED) + serverOps(OpType.REVIEW_LOGGED))
        assertEquals(1, serverOps(OpType.WORD_STATUS_SET))
        assertEquals(0, r["notes_created"].asInt() + r["cards_created"].asInt() + r["kanji_notes_created"].asInt() + r["kanji_cards_created"].asInt())
        assertEquals(3 + 13, r["reviews_skipped"].asInt(), "rating events count as skipped")
        assertSums(r)
    }

    @Test
    fun importWordStatusFalseWritesNoStatus() {
        val p = plan
        (p["options"] as ObjectNode).put("import_word_status", false)
        val r = run(p)
        assertEquals(0, serverOps(OpType.WORD_STATUS_SET))
        assertEquals(0, r["word_statuses_set"].asInt())
        assertEquals(13, r["reviews_imported"].asInt())
        assertSums(r)
    }

    @Test
    fun anExistingOverrideIsNeverOverwritten() {
        emit(OpType.WORD_STATUS_SET, """{"entry_seq":1381820,"status":"blacklisted"}""")
        val r = run()
        assertEquals(0, r["word_statuses_set"].asInt())
        assertEquals("blacklisted", db.txBlocking { it.fetchValue("select override_status from app.word_status where entry_seq = 1381820") as String })
    }

    @Test
    fun anExistingNoteAndCardAreReusedAndAnUnsuspendedWritingCardStaysUnsuspended() {
        val first = run()
        assertEquals(1, first["kanji_cards_suspended"].asInt())
        val gaku = assertNotNull(liveNote("kanji_literal", "学"))
        val writing = assertNotNull(card(gaku, "writing"))["id"] as String
        emit(OpType.CARD_UNSUSPENDED, """{"id":"$writing"}""")
        val second = run()
        assertEquals(false, card(gaku, "writing")!!["suspended"], "a re-run never re-suspends")
        assertEquals(0, second["kanji_cards_suspended"].asInt())
    }

    @Test
    fun aDeletedNoteIsNotReusedTheImportCreatesANewOneAndItsReviewsCountAsAlreadyImported() {
        run()
        val gakkou = assertNotNull(liveNote("entry_seq", 1206730L))
        emit(OpType.NOTE_DELETED, """{"id":"$gakkou"}""")
        val r = run()
        assertEquals(1, r["notes_created"].asInt())
        assertEquals(3, r["notes_existing"].asInt())
        // Review ids are global: the deleted card's rows already hold them, so nothing is re-logged (contract:
        // "already exists in review_log, any state"); a tombstoned owner is not an id collision.
        assertEquals(0, r["reviews_imported"].asInt())
        assertEquals(13, r["reviews_already_imported"].asInt())
        assertEquals(0, r["reviews_id_collisions"].asInt())
        assertSums(r)
    }

    // ------------------------------------------------------------------ edge cases from FOLLOWUPS

    @Test
    fun aReviewIdCollisionBetweenTwoGroupsIsCountedNotDropped() {
        val p = plan
        val groups = p["vocab_groups"] as ArrayNode
        val gakkou = groups.first { it["entry_seq"].asLong() == 1206730L }
        val neko = groups.first { it["entry_seq"].asLong() == 1467640L }
        // One vid text-mapped to two seqs, same timestamp + grade + occurrence: the v1 id rule gives one id twice.
        (neko["reviews"] as ArrayNode).add(gakkou["reviews"][0].deepCopy<JsonNode>())
        (p["counts"] as ObjectNode).put("reviews_rating", 14).put("reviews_total", 25)
        val r = run(p)
        assertEquals(13, r["reviews_imported"].asInt())
        assertEquals(1, r["reviews_already_imported"].asInt())
        assertEquals(1, r["reviews_id_collisions"].asInt())
        assertSums(r)
    }

    @Test
    fun aKanjiWithNulInItsCharacterIsSkippedAndCounted() {
        val p = plan
        (p["kanji_notes"] as ArrayNode).add(json.readTree("""{"character":"\u0000","fields":{"literal":"\u0000"}}"""))
        val ratings = json.readTree(
            """[{"id":"${Uuid7.generate()}","reviewed_at":1770400000000,"rating":3,"button":"okay","grade":"okay",
               "utc_offset_min":null,"duration_ms":null,"review_type":"scheduled","import_source":"jpdb"}]""",
        )
        (p["kanji_groups"] as ArrayNode).add(
            json.createObjectNode().put("character", "\u0000").put("card_kind", "keyword").put("known_only", false)
                .put("suspended_on_create", false).put("row_count", 1).set<JsonNode>("reviews", ratings),
        )
        (p["counts"] as ObjectNode).put("kanji_total", 3).put("kanji_carded", 3).put("reviews_rating", 14).put("reviews_total", 25)
        val r = run(p)
        assertEquals(2, r["kanji_carded"].asInt())
        assertEquals(1, r["kanji_unmapped"].size())
        assertEquals(1, r["kanji_unmapped"][0]["review_count"].asInt())
        assertEquals(3 + 1, r["reviews_skipped"].asInt())
        assertEquals(2, r["kanji_notes_created"].asInt())
        assertSums(r)
    }

    @Test
    fun aKeywordWithNulIsStrippedFromTheField() {
        val p = plan
        (p["kanji_notes"][0]["fields"] as ObjectNode).put("keyword", "c\u0000at")
        run(p)
        val neko = assertNotNull(liveNote("kanji_literal", "猫"))
        assertEquals("cat", db.txBlocking { it.fetchValue("select fields->>'keyword' from app.note where id = ?::uuid", neko) as String })
    }

    @Test
    fun aPlanWhoseCountsDisagreeWithItsReviewsFailsBeforeWriting() {
        val p = plan
        (p["counts"] as ObjectNode).put("reviews_rating", 12)
        assertFailsWith<IllegalStateException> { run(p) }
        assertEquals(0, serverOps(OpType.NOTE_CREATED))
        val v2 = plan.put("version", 2)
        assertFailsWith<IllegalArgumentException> { run(v2) }
    }

    @Test
    fun cancelStopsAtABatchBoundaryAndARerunResumes() {
        var batches = 0
        val cancelAfterOne = object : ImportProgress {
            override fun report(fraction: Double, message: String) { batches++ }
            override fun cancelled() = batches >= 1
        }
        assertFailsWith<ImportCancelled> { run(progress = cancelAfterOne) }
        assertEquals(2, serverOps(OpType.NOTE_CREATED), "one batch of two vocab groups committed")
        val r = run()
        assertEquals(2, r["notes_existing"].asInt())
        assertEquals(4, r["notes_created"].asInt() + r["kanji_notes_created"].asInt())
        assertEquals(13, r["reviews_imported"].asInt() + r["reviews_already_imported"].asInt())
        assertEquals(13, count("select count(*) from app.review_log"))
        assertSums(r)
    }

    // ------------------------------------------------------------------ job bridge and endpoint

    private fun insertJob(kind: String, status: String, payload: String, lockedBy: String? = null): UUID {
        val id = Uuid7.generate()
        db.txBlocking {
            it.execute(
                "insert into app.job (id, kind, status, payload, locked_by, heartbeat_at, started_at, finished_at, max_attempts) " +
                    "values (?, ?, ?, ?::jsonb, ?, now(), case when ? <> 'queued' then now() end, " +
                    "case when ? in ('succeeded','failed','cancelled') then now() end, 1)",
                id, kind, status, payload, lockedBy, status, status,
            )
        }
        return id
    }

    private fun job(id: UUID): Map<String, Any?> = db.txBlocking {
        it.fetchOne("select status, result::text as result, error, progress, payload::text as payload from app.job where id = ?", id)!!.intoMap()
    }

    @Test
    fun theApplyRunnerAppliesAPlannedJobAndHandsBackTheResult() {
        sync.opApply // startup sequence
        val parent = insertJob("jpdb-history-import", "running", """{"blob_hash":"${"a".repeat(64)}"}""", lockedBy = "worker-1")
        val child = insertJob(
            JpdbHistoryApplyRunner.INTERNAL_KIND, "queued",
            json.writeValueAsString(mapOf("parent_job_id" to parent.toString(), "plan" to plan)),
        )
        val runner = JpdbHistoryApplyRunner(db, JpdbHistoryApply(sync.opApply, batchUnits = 3))
        assertTrue(runner.runOnce())
        assertFalse(runner.runOnce(), "nothing left to claim")
        val c = job(child)
        assertEquals("succeeded", c["status"])
        val result = json.readTree(c["result"] as String)
        assertEquals(13, result["reviews_imported"].asInt())
        assertFalse(json.readTree(c["payload"] as String).has("plan"), "the plan is dropped once applied")
        assertTrue((job(parent)["progress"] as Number).toDouble() > 0.2, "the public job's progress moves")
        assertEquals("running", job(parent)["status"], "the worker, not the server, finishes the public job")
    }

    @Test
    fun theApplyRunnerStopsWhenThePublicJobWasCancelled() {
        sync.opApply
        val parent = insertJob("jpdb-history-import", "cancelled", "{}")
        val child = insertJob(JpdbHistoryApplyRunner.INTERNAL_KIND, "queued", json.writeValueAsString(mapOf("parent_job_id" to parent.toString(), "plan" to plan)))
        assertTrue(JpdbHistoryApplyRunner(db, importer).runOnce())
        assertEquals("cancelled", job(child)["status"])
        assertEquals(0, serverOps(OpType.NOTE_CREATED))
    }

    @Test
    fun theApplyRunnerFailsABadPlanWithItsError() {
        sync.opApply
        val parent = insertJob("jpdb-history-import", "running", "{}", lockedBy = "w")
        val child = insertJob(JpdbHistoryApplyRunner.INTERNAL_KIND, "queued", """{"parent_job_id":"$parent","plan":{"version":9}}""")
        assertTrue(JpdbHistoryApplyRunner(db, importer).runOnce())
        val c = job(child)
        assertEquals("failed", c["status"])
        assertTrue((c["error"] as String).contains("version"))
    }

    @Test
    fun importJpdbHistoryEnqueuesOneJobAndValidatesTheBlob() {
        val imports = ImportsModule(db, sync.opApply)
        val hash = "b".repeat(64)
        app(extraHandlers = imports.handlers) {
            val t = mint(TokenKind.PERSONAL, listOf("read", "import")).second
            val missing = client.call(HttpMethod.Post, "/import/jpdb/history", bearer = t, body = mapOf("blob_hash" to hash))
            assertEquals(422, missing.status.value)
            db.txBlocking { it.execute("insert into app.blob (hash, size_bytes, content_type, kind) values (?, 10, 'application/json', 'upload')", hash) }
            val first = client.call(HttpMethod.Post, "/import/jpdb/history", bearer = t, body = mapOf("blob_hash" to hash, "import_word_status" to false))
            assertEquals(202, first.status.value)
            val job = first.json()
            assertEquals("jpdb-history-import", job["kind"].asText())
            assertEquals(false, job["payload"]["import_word_status"].asBoolean())
            assertEquals(true, job["payload"]["import_reviews"].asBoolean())
            val again = client.call(HttpMethod.Post, "/import/jpdb/history", bearer = t, body = mapOf("blob_hash" to hash))
            assertEquals(200, again.status.value)
            assertEquals(job["id"], again.json()["id"])
            val bad = client.call(HttpMethod.Post, "/import/jpdb/history", bearer = t, body = mapOf("blob_hash" to "XYZ", "import_reviews" to "yes"))
            assertEquals(422, bad.status.value)
        }
    }
}
