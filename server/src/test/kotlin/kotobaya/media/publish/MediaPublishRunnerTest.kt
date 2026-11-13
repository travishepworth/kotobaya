package kotobaya.media.publish

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.blobs.InMemoryBlobStore
import kotobaya.contracts.sync.OpType
import kotobaya.media.publish.Archives.archive
import kotobaya.media.publish.Archives.records
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.SyncFixture
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T411c: the media-publish runner (MEDIA_PIPELINE.md 3.3-3.5, 4, 5.2) against a real Postgres.
 *
 * Fixtures: the C-11 examples. v1 = `new-media` as published (base 0); v2 = the same media re-parsed: segment 0 and
 * its sentences keep their ids, segment 1 gets a new id and its sentence a new id, no detected span.
 */
class MediaPublishRunnerTest : SyncFixture() {
    private val store = InMemoryBlobStore()
    private val faults = mutableListOf<(String) -> Unit>()
    private val runner by lazy {
        MediaPublishRunner(db, sync.opApply, store, instanceId = "test") { stage -> faults.forEach { it(stage) } }
    }

    // ------------------------------------------------------------------ fixture helpers

    private fun blob(bytes: ByteArray): ObjectNode {
        val hash = Archives.sha256(bytes)
        store.put(hash, bytes.inputStream(), "application/zstd")
        db.txBlocking {
            it.execute(
                "insert into app.blob (hash, size_bytes, content_type, kind, origin) values (?, ?, 'application/zstd', 'snapshot', 'build') on conflict do nothing",
                hash, bytes.size.toLong(),
            )
        }
        return json.createObjectNode().put("blob_hash", hash).put("size_bytes", bytes.size)
    }

    private fun parent(mediaId: String, kind: String = "media-import", status: String = "running"): UUID {
        val id = Uuid7.generate()
        val finished = if (status in setOf("succeeded", "failed", "cancelled")) "now()" else "null"
        db.txBlocking {
            it.execute(
                "insert into app.job (id, kind, payload, status, started_at, finished_at) values (?, ?, ?::jsonb, ?, now(), $finished)",
                id, kind, """{"media_id":"$mediaId"}""", status,
            )
        }
        return id
    }

    /** Inserts the child as the worker does (3.1) and returns its id. */
    private fun child(payload: ObjectNode): UUID {
        val id = Uuid7.generate()
        db.txBlocking {
            it.execute(
                "insert into app.job (id, kind, payload, dedupe_key, max_attempts) values (?, 'media-publish', ?::jsonb, ?, 1)",
                id, json.writeValueAsString(payload), "media-publish:" + payload["parent_job_id"].asText(),
            )
        }
        return id
    }

    private fun payload(example: String, content: ByteArray, deck: ByteArray, parent: UUID): ObjectNode =
        Archives.payload(example).apply {
            put("parent_job_id", parent.toString())
            set<JsonNode>("content", blob(content))
            set<JsonNode>("deck_items", blob(deck))
        }

    private fun job(id: UUID): Map<String, Any?> = db.txBlocking {
        it.fetchOne("select status, error, result::text as result, progress, progress_message from app.job where id = ?", id)!!.intoMap()
    }

    private fun assertJob(status: String, id: UUID, error: String? = null) {
        val j = job(id)
        assertEquals(status, j["status"], "job $id: $j")
        if (error != null) assertTrue((j["error"] as String?)?.startsWith(error) == true, "error ${j["error"]} should start with $error")
    }

    private data class Logged(val type: String, val opId: String, val causedBy: String?, val payload: JsonNode)

    private fun ops(): List<Logged> = db.txBlocking { dsl ->
        dsl.fetch("select op_type, op_id::text, caused_by_op_id::text, payload::text from app.change order by seq").map {
            Logged(it.get(0, String::class.java), it.get(1, String::class.java), it.get(2, String::class.java), json.readTree(it.get(3, String::class.java)))
        }
    }

    private fun mediaOps(): List<Logged> = ops().filter { it.type.startsWith("media.") || it.type.startsWith("deck.") }

    private fun rows(sql: String, vararg args: Any?): List<Map<String, Any?>> = db.txBlocking { it.fetch(sql, *args).intoMaps() }

    /** Everything a publish writes for [mediaId], for row-identity comparisons. */
    private fun state(mediaId: String): List<Any?> = listOf(
        rows("select * from app.media where id = ?::uuid", mediaId),
        rows("select * from app.deck where id = ?::uuid", mediaId),
        rows("select * from app.media_segment where media_id = ?::uuid order by ordinal", mediaId),
        rows("select id, segment_id, ordinal, text, tokens::text, origin, start_ms, end_ms from app.sentence where media_id = ?::uuid order by id", mediaId),
        rows("select segment_id, entry_seq, count, pos_class, sentence_ids::text from app.occurrence where media_id = ?::uuid order by segment_id, entry_seq", mediaId),
        rows("select sentence_id, grammar_point_id, start_offset, end_offset, origin, rule_index, deleted_at from app.sentence_grammar_span order by 1, 2, 3, 4, 5"),
        rows("select * from app.deck_item where deck_id = ?::uuid order by item_key", mediaId),
    )

    private fun grammarPoint() = emit(OpType.GRAMMAR_CREATED, """{"id":"$GP","title":"だ"}""")

    private fun publishV1(parent: UUID = parent(MEDIA)): UUID {
        val c = child(payload("media-publish.new-media.json", Archives.bytes("new-media.content.v1.jsonl.zst"), Archives.bytes("new-media.deck-items.v1.jsonl.zst"), parent))
        assertTrue(runner.runOnce())
        return c
    }

    private fun v2Content(title: String = "第2話"): ByteArray {
        val r = records("new-media.content.v1.jsonl").filter { it["record"].asText() != "grammar_span" }.toMutableList()
        r[0].put("content_version", 2)
        for (x in r) {
            if (x["record"].asText() == "segment" && x["id"].asText() == SEG1) x.put("id", SEG1_V2).put("title", title)
            if (x.path("segment_id").asText() == SEG1) x.put("segment_id", SEG1_V2)
            if (x["record"].asText() == "sentence" && x["id"].asText() == SENT_B2) x.put("id", SENT_B3)
            if (x["record"].asText() == "occurrence") {
                val ids = x.withArrayProperty("sentence_ids")
                for (i in 0 until ids.size()) if (ids[i].asText() == SENT_B2) ids.set(i, json.nodeFactory.textNode(SENT_B3))
            }
        }
        return archive(Archives.recount(r))
    }

    private fun v2Deck(): ByteArray = archive(records("new-media.deck-items.v1.jsonl").also { it[0].put("content_version", 2) })

    private fun publishV2(parent: UUID = parent(MEDIA, "media-parse"), content: ByteArray = v2Content()): UUID {
        val p = payload("media-publish.new-media.json", content, v2Deck(), parent)
        p.put("base_content_version", 1).putNull("create")
        val c = child(p)
        assertTrue(runner.runOnce())
        return c
    }

    // ------------------------------------------------------------------ order, first vs second publish, parse_status

    @Test
    fun firstPublishMintsMediaCreatedThenDeckCreatedThenContentOps() {
        grammarPoint()
        val parent = parent(MEDIA)
        val c = publishV1(parent)
        assertJob("succeeded", c)

        val o = mediaOps()
        val types = o.map { it.type }
        assertEquals(listOf("media.created", "deck.created"), types.take(2))
        assertEquals(listOf("media.content_published", "deck.content_published"), types.takeLast(2))
        // While OpApplier.mediaCreated still returns its deck follow-up, the options follow as one deck.updated.
        assertTrue(types.size == 4 || (types.size == 5 && types[2] == "deck.updated"), "ops: $types")
        assertEquals(o[0].opId, o[1].causedBy, "deck.created is caused by media.created")
        assertNull(o[0].causedBy)
        assertEquals(MEDIA, o[1].payload["id"].asText())
        assertEquals("media", o[1].payload["type"].asText())
        val published = o.first { it.type == "media.content_published" }.payload
        assertEquals("parsed", published["parse_status"].asText())
        assertEquals(1, published["content_version"].asInt())
        assertEquals(CONTENT_V1, published["content_blob_hash"].asText())
        assertEquals(DECK_V1, o.last().payload["content_blob_hash"].asText())

        val media = row("media", "id" to MEDIA)!!
        assertEquals("parsed", media["parse_status"].asText())
        assertEquals(1, media["content_version"].asInt())
        assertEquals("葬送のフリーレン", media["title"].asText())
        val deck = row("deck", "id" to MEDIA)!!
        assertEquals("media", deck["type"].asText())
        assertEquals(5, deck["priority"].asInt())
        assertEquals(true, deck["enabled"].asBoolean())
        assertEquals(10, deck["new_cards_per_day"].asInt())
        assertEquals("deck_frequency", deck["settings"]["order"].asText())
        assertEquals(1, deck["content_version"].asInt())

        // Rows installed (3.5): 2 segments, 3 sentences, 11 occurrences, 1 detected span, 6 deck items.
        assertEquals(2, count("select count(*) from app.media_segment where media_id = ?::uuid", MEDIA))
        assertEquals(3, count("select count(*) from app.sentence where media_id = ?::uuid and unknown_count is null", MEDIA))
        assertEquals(11, count("select count(*) from app.occurrence where media_id = ?::uuid", MEDIA))
        assertEquals(1, count("select count(*) from app.sentence_grammar_span where media_id = ?::uuid and origin = 'detected'", MEDIA))
        assertEquals(6, count("select count(*) from app.deck_item d join app.deck k on k.id = d.deck_id where d.deck_id = ?::uuid and d.added_at = k.created_at and d.hlc is null", MEDIA))
        assertEquals(
            "[\"0193a1b2-0007-7000-8000-0000000005b0\"]",
            json.writeValueAsString(json.readTree(rows("select array_to_json(sentence_ids)::text as s from app.occurrence where entry_seq = 1101220 and segment_id = ?::uuid", SEG0)[0]["s"] as String)),
        )

        // JobResultMedia on the child (the worker copies it to the parent); the parent shows "publishing".
        val result = json.readTree(job(c)["result"] as String)
        assertEquals(json.readTree("""{"media_id":"$MEDIA","content_version":1,"segments":2,"sentences":3,"tokens":12,"unmapped_tokens":1,"unique_entries":6,"unmapped":[]}"""), result)
        assertEquals("publishing", job(parent)["progress_message"])
        assertEquals(0.95f, job(parent)["progress"] as Float, 0.0001f)
    }

    @Test
    fun secondPublishMintsOnlyContentOpsWithTheNextVersionAndParseStatusFollowsC11() {
        grammarPoint()
        publishV1()
        val before = ops().size
        // API (T411b) for reparseMedia: parsing, same version and hash.
        emit(OpType.MEDIA_CONTENT_PUBLISHED, """{"media_id":"$MEDIA","parse_status":"parsing","parse_error":null,"content_version":1,"content_blob_hash":"$CONTENT_V1"}""")
        assertEquals("parsing", row("media", "id" to MEDIA)!!["parse_status"].asText())

        val c = publishV2()
        assertJob("succeeded", c)
        val added = ops().drop(before + 1)
        assertEquals(listOf("media.content_published", "deck.content_published"), added.map { it.type })
        assertEquals(2, added[0].payload["content_version"].asInt())
        assertEquals("parsed", added[0].payload["parse_status"].asText())
        assertEquals(2, added[1].payload["content_version"].asInt())
        val media = row("media", "id" to MEDIA)!!
        assertEquals("parsed", media["parse_status"].asText())
        assertEquals(2, media["content_version"].asInt())
        assertEquals(2, row("deck", "id" to MEDIA)!!["content_version"].asInt())
        // pending -> parsed (v1) -> parsing (v1) -> parsed (v2), in seq order.
        val statuses = ops().filter { it.type == "media.content_published" }.map { it.payload["parse_status"].asText() + "@" + it.payload["content_version"].asInt() }
        assertEquals(listOf("parsed@1", "parsing@1", "parsed@2"), statuses)
    }

    @Test
    fun grammarDetectParentKeepsParseStatusAndRefusesCreate() {
        grammarPoint()
        publishV1(parent(MEDIA, "grammar-detect")).let { assertJob("failed", it, "invalid_payload") }
        assertEquals(0, count("select count(*) from app.media"), "a grammar-detect publish never creates")

        publishV1()
        emit(OpType.MEDIA_CONTENT_PUBLISHED, """{"media_id":"$MEDIA","parse_status":"parsing","parse_error":null,"content_version":1,"content_blob_hash":"$CONTENT_V1"}""")
        publishV2(parent(MEDIA, "grammar-detect")).let { assertJob("succeeded", it) }
        val media = row("media", "id" to MEDIA)!!
        assertEquals(2, media["content_version"].asInt())
        assertEquals("parsing", media["parse_status"].asText(), "detection is not a parse")
        assertEquals("parsing", ops().last { it.type == "media.content_published" }.payload["parse_status"].asText())
    }

    // ------------------------------------------------------------------ ids across re-publish, manual spans

    @Test
    fun sentenceIdsAreKeptOldRowsAreGoneAndManualSpansUntouched() {
        grammarPoint()
        publishV1()
        val manual = listOf(SENT_B0, SENT_B2).map { sid ->
            emit(OpType.GRAMMAR_SPAN_PUT, """{"sentence_id":"$sid","grammar_point_id":"$GP","start_offset":0,"end_offset":2,"media_id":"$MEDIA"}""")
        }
        assertEquals(2, manual.size)
        val manualBefore = rows("select * from app.sentence_grammar_span where origin = 'manual' order by sentence_id")

        assertJob("succeeded", publishV2())
        val ids = rows("select id::text as id from app.sentence where media_id = ?::uuid order by id", MEDIA).map { it["id"] }
        assertEquals(listOf(SENT_B0, SENT_B1, SENT_B3), ids, "kept ids from the snapshot, B2 replaced by B3")
        assertEquals(listOf(SEG0, SEG1_V2), rows("select id::text as id from app.media_segment where media_id = ?::uuid order by ordinal", MEDIA).map { it["id"] })
        assertEquals(0, count("select count(*) from app.occurrence where segment_id = ?::uuid", SEG1))
        assertEquals(5, count("select count(*) from app.occurrence where segment_id = ?::uuid", SEG1_V2))
        assertEquals(0, count("select count(*) from app.sentence_grammar_span where origin = 'detected'"), "v2 has no detected span")
        assertEquals(manualBefore, rows("select * from app.sentence_grammar_span where origin = 'manual' order by sentence_id"), "manual spans untouched")
        assertEquals(6, count("select count(*) from app.deck_item where deck_id = ?::uuid", MEDIA))
    }

    // ------------------------------------------------------------------ idempotency (CLAUDE.md: op apply)

    @Test
    fun runningTheSameChildTwiceYieldsOneSetOfOpsAndIdenticalRows() {
        grammarPoint()
        val c = publishV1()
        assertJob("succeeded", c)
        val opsAfter = ops()
        val stateAfter = state(MEDIA)
        val resultAfter = job(c)["result"]

        // Re-claimed (as if its succeeded status had been lost): it changes nothing and reports the same result.
        db.txBlocking { it.execute("update app.job set status = 'queued', finished_at = null, result = null, locked_by = null where id = ?", c) }
        assertTrue(runner.runOnce())
        assertJob("succeeded", c)
        assertEquals(resultAfter, job(c)["result"])
        assertEquals(opsAfter, ops())
        assertEquals(stateAfter, state(MEDIA))

        // Same for a second publish.
        val c2 = publishV2()
        val ops2 = ops()
        val state2 = state(MEDIA)
        db.txBlocking { it.execute("update app.job set status = 'queued', finished_at = null, result = null, locked_by = null where id = ?", c2) }
        assertTrue(runner.runOnce())
        assertJob("succeeded", c2)
        assertEquals(ops2, ops())
        assertEquals(state2, state(MEDIA))
    }

    @Test
    fun aCrashMidTransactionLeavesNoRowsAndNoOps() {
        grammarPoint()
        val before = ops()
        faults += { if (it == "installed") throw IllegalStateException("simulated crash") }
        val c = publishV1()
        assertJob("failed", c, "internal: simulated crash")
        assertEquals(before, ops())
        assertNull(row("media", "id" to MEDIA))
        assertNull(row("deck", "id" to MEDIA))
        assertEquals(0, count("select count(*) from app.media_segment"))
        assertEquals(0, count("select count(*) from app.sentence"))
        assertEquals(0, count("select count(*) from app.occurrence"))
        assertEquals(0, count("select count(*) from app.sentence_grammar_span"))
        assertEquals(0, count("select count(*) from app.deck_item"))
    }

    @Test
    fun aChildLeftRunningByADeadServerIsRequeuedAndRunFromTheStart() {
        grammarPoint()
        val c = child(payload("media-publish.new-media.json", Archives.bytes("new-media.content.v1.jsonl.zst"), Archives.bytes("new-media.deck-items.v1.jsonl.zst"), parent(MEDIA)))
        db.txBlocking {
            it.execute("update app.job set status = 'running', attempts = 1, locked_by = 'dead', started_at = now() - interval '1 hour', heartbeat_at = now() - interval '6 minutes' where id = ?", c)
        }
        assertTrue(runner.runOnce())
        assertJob("succeeded", c)
        assertEquals(2, count("select attempts from app.job where id = ?", c))
        assertEquals(1, count("select count(*) from app.change where op_type = 'media.created'"))
    }

    @Test
    fun parentCancelledNothingIsApplied() {
        grammarPoint()
        val before = ops()
        val c = publishV1(parent(MEDIA, status = "cancelled"))
        assertJob("cancelled", c)
        assertEquals(before, ops())
        assertNull(row("media", "id" to MEDIA))

        // Cancelled after the archives were checked, before the transaction: the re-check in step 4 sees it.
        val p = parent(MEDIA)
        faults += { if (it == "fetched") db.txBlocking { d -> d.execute("update app.job set status = 'cancelled', finished_at = now() where id = ?", p) } }
        val c2 = publishV1(p)
        assertJob("cancelled", c2)
        assertEquals(before, ops())
        assertNull(row("media", "id" to MEDIA))
        assertEquals(0, count("select count(*) from app.media_segment"))
    }

    @Test
    fun aLostClaimRollsBackAndLeavesTheChildToItsNewOwner() {
        grammarPoint()
        val before = ops()
        faults += { if (it == "fetched") db.txBlocking { d -> d.execute("update app.job set locked_by = 'other' where kind = 'media-publish'") } }
        val c = publishV1()
        assertEquals("running", job(c)["status"])
        assertEquals(before, ops())
        assertNull(row("media", "id" to MEDIA))
    }

    // ------------------------------------------------------------------ failures (3.3 steps 1-5)

    @Test
    fun aUserDeckHoldingTheMediaIdFailsTheFirstPublishLoudly() {
        grammarPoint()
        // A user deck already holds the media's id (T202b verify): deck_id_taken, nothing installed.
        emit(OpType.DECK_CREATED, """{"id":"$MEDIA","name":"mine","type":"user"}""")
        val before = ops()
        assertJob("failed", publishV1(), "deck_id_taken:")
        assertEquals(before, ops())
        assertNull(row("media", "id" to MEDIA))
    }

    @Test
    fun aPublishOnAMovedBaseIsStale() {
        grammarPoint()
        publishV1()
        assertJob("succeeded", publishV2())
        val before = ops()
        // Another re-parse built on v1 with different content: the media is at v2 now.
        assertJob("failed", publishV2(content = v2Content("第2話 改")), "stale_base:")
        assertEquals(before, ops())
        // A non-create publish for a media that does not exist.
        val p = payload("media-publish.new-media.json", v2Content(), v2Deck(), parent(OTHER))
        p.put("base_content_version", 1).putNull("create").put("media_id", OTHER)
        child(p)
        runner.runOnce()
        assertEquals(1, count("select count(*) from app.job where kind = 'media-publish' and error like 'invalid_payload:%'"), "header media_id != payload")
    }

    @Test
    fun aSentenceIdOfAnotherMediaIsAnInvalidSnapshot() {
        grammarPoint()
        publishV1()
        // The same sentence ids under another media id (segments renamed).
        val r = records("new-media.content.v1.jsonl")
        r[0].put("media_id", OTHER)
        for (x in r) {
            if (x["record"].asText() == "segment") x.put("id", x["id"].asText().replace("-0007-", "-0009-"))
            if (x.has("segment_id")) x.put("segment_id", x["segment_id"].asText().replace("-0007-", "-0009-"))
        }
        val d = records("new-media.deck-items.v1.jsonl").also { it[0].put("deck_id", OTHER) }
        val p = payload("media-publish.new-media.json", archive(r), archive(d), parent(OTHER)).put("media_id", OTHER)
        val c = child(p)
        runner.runOnce()
        assertJob("failed", c, "invalid_snapshot: sentence")
        assertNull(row("media", "id" to OTHER))
    }

    @Test
    fun payloadAndBlobProblemsAreReportedWithTheirCodes() {
        grammarPoint()
        val base = payload("media-publish.new-media.json", Archives.bytes("new-media.content.v1.jsonl.zst"), Archives.bytes("new-media.deck-items.v1.jsonl.zst"), parent(MEDIA))
        val unsupported = child(base.deepCopy().put("format_version", 2).put("parent_job_id", parent(MEDIA).toString()))
        runner.runOnce()
        assertJob("failed", unsupported, "unsupported_format:")
        val extra = child(base.deepCopy().put("extra", 1).put("parent_job_id", parent(MEDIA).toString()))
        runner.runOnce()
        assertJob("failed", extra, "invalid_payload:")
        val missing = base.deepCopy().put("parent_job_id", parent(MEDIA).toString())
        missing.withObjectProperty("content").put("blob_hash", "0".repeat(64))
        val m = child(missing)
        runner.runOnce()
        assertJob("failed", m, "blob_missing:")
        // An object whose bytes do not hash to its key.
        val corrupt = "1".repeat(64)
        val bytes = Archives.bytes("new-media.content.v1.jsonl.zst")
        store.put(corrupt, bytes.inputStream(), "application/zstd")
        db.txBlocking { it.execute("insert into app.blob (hash, size_bytes, content_type, kind, origin) values (?, ?, 'application/zstd', 'snapshot', 'build')", corrupt, bytes.size.toLong()) }
        val bad = base.deepCopy().put("parent_job_id", parent(MEDIA).toString())
        bad.withObjectProperty("content").put("blob_hash", corrupt)
        val b = child(bad)
        runner.runOnce()
        assertJob("failed", b, "invalid_snapshot:")
        assertNull(row("media", "id" to MEDIA))
    }

    // ------------------------------------------------------------------ jpdb mapping queue (5.2)

    private fun jpdbPayload(version: Int, vocab: List<ObjectNode>, parent: UUID, mediaId: String = JPDB_MEDIA): ObjectNode {
        val c = records("jpdb-reimport.content.v1.jsonl").also { it[0].put("content_version", version).put("media_id", mediaId) }
        val d = records("jpdb-reimport.deck-items.v1.jsonl").also { it[0].put("content_version", version).put("deck_id", mediaId) }
        if (mediaId != JPDB_MEDIA) c.filter { it.has("segment_id") || it["record"].asText() == "segment" }.forEach { x ->
            if (x.has("segment_id")) x.put("segment_id", OTHER_SEG) else x.put("id", OTHER_SEG)
        }
        val p = payload("media-publish.jpdb-reimport.json", archive(c), archive(d), parent)
        p.put("base_content_version", version - 1).put("media_id", mediaId)
        if (version == 1) {
            p.set<JsonNode>("create", json.readTree("""{"title":"Frieren (jpdb)","kind":"anime","source":"jpdb","jpdb_id":"anime/5370","deck":{"priority":0,"enabled":true,"new_cards_per_day":null}}"""))
        }
        p.withObjectProperty("jpdb").putArray("vocab").addAll(vocab)
        val unmapped = p.putArray("unmapped")
        vocab.filter { it["resolution"].asText() == "unresolved" }.forEach { v ->
            unmapped.addObject().put("jpdb_vid", v["jpdb_vid"].asLong()).put("spelling", v["spelling"].asText()).put("reading", v["reading"].asText())
                .put("occurrence_count", v["occurrence_count"].asInt())
        }
        return p
    }

    @Test
    fun jpdbPublishPopulatesTheMappingQueueAndNeverTouchesResolutions() {
        val vocab = Archives.payload("media-publish.jpdb-reimport.json")["jpdb"]["vocab"].map { it as ObjectNode }
        val c1 = child(jpdbPayload(1, vocab, parent(JPDB_MEDIA, "jpdb-deck-import")))
        runner.runOnce()
        assertJob("succeeded", c1)
        assertEquals("jpdb", row("media", "id" to JPDB_MEDIA)!!["source"].asText())
        assertEquals(
            listOf(mapOf("jpdb_vid" to 9300001L, "spelling" to "フリーレン", "resolution" to "unresolved"), mapOf("jpdb_vid" to 9300002L, "spelling" to "ヒンメル", "resolution" to "unresolved"), mapOf("jpdb_vid" to 9300003L, "spelling" to "魔族", "resolution" to "unresolved")),
            rows("select jpdb_vid, spelling, resolution from app.jpdb_vocab order by jpdb_vid"),
            "rows inserted; the payload's resolution is informational",
        )
        assertEquals(
            listOf(9300001L to 312, 9300002L to 40, 9300003L to 75),
            rows("select jpdb_vid, occurrence_count from app.jpdb_vocab_media where media_id = ?::uuid order by jpdb_vid", JPDB_MEDIA).map { it["jpdb_vid"] to it["occurrence_count"] },
        )
        val result = json.readTree(job(c1)["result"] as String)
        assertEquals(Archives.payload("media-publish.jpdb-reimport.json")["unmapped"], result["unmapped"])
        assertEquals(352, result["unmapped_tokens"].asInt())

        // The user ignores 9300002 (API); the next publish drops 9300003 from the list and renames 9300001.
        db.txBlocking { it.execute("update app.jpdb_vocab set resolution = 'ignored', resolved_at = now() where jpdb_vid = 9300002") }
        val v2 = listOf(vocab[0].deepCopy().put("spelling", "フリーレン様"), vocab[2].deepCopy())
        val c2 = child(jpdbPayload(2, v2, parent(JPDB_MEDIA, "jpdb-deck-import")))
        runner.runOnce()
        assertJob("succeeded", c2)
        assertEquals(3, count("select count(*) from app.jpdb_vocab"), "rows are never deleted")
        assertEquals("ignored", rows("select resolution from app.jpdb_vocab where jpdb_vid = 9300002")[0]["resolution"])
        assertEquals("フリーレン様", rows("select spelling from app.jpdb_vocab where jpdb_vid = 9300001")[0]["spelling"])
        assertEquals(
            listOf(9300001L, 9300002L),
            rows("select jpdb_vid from app.jpdb_vocab_media where media_id = ?::uuid order by jpdb_vid", JPDB_MEDIA).map { it["jpdb_vid"] },
            "links replaced by the latest publish",
        )
        // A second new media with the same live jpdb_id is refused.
        val c3 = child(jpdbPayload(1, vocab, parent(OTHER, "jpdb-deck-import"), mediaId = OTHER))
        runner.runOnce()
        assertJob("failed", c3, "jpdb_id_taken:")
    }

    // ------------------------------------------------------------------ reconciler (4)

    @Test
    fun theReconcilerEndsAStuckParsingStatus() {
        grammarPoint()
        publishV1()
        fun parsing() = emit(OpType.MEDIA_CONTENT_PUBLISHED, """{"media_id":"$MEDIA","parse_status":"parsing","parse_error":null,"content_version":1,"content_blob_hash":"$CONTENT_V1"}""")
        fun status() = row("media", "id" to MEDIA)!!.let { it["parse_status"].asText() to it["parse_error"].takeIf { e -> !e.isNull }?.asText() }

        // The import that created the media is over (the worker ended it with the child's result).
        db.txBlocking { it.execute("update app.job set status = 'succeeded', finished_at = now() - interval '1 hour' where kind = 'media-import'") }

        // A queued job for the media: still being rebuilt, nothing to do.
        parsing()
        val queued = Uuid7.generate()
        db.txBlocking { it.execute("insert into app.job (id, kind, payload) values (?, 'media-parse', ?::jsonb)", queued, """{"media_id":"$MEDIA"}""") }
        assertEquals(0, runner.reconciler.reconcileAll())
        assertEquals("parsing" to null, status())

        // The job failed: failed, with its error, same version and hash.
        db.txBlocking { it.execute("update app.job set status = 'failed', error = 'parse: boom', finished_at = now() + interval '1 hour' where id = ?", queued) }
        assertEquals(1, runner.reconciler.reconcileAll())
        assertEquals("failed" to "parse: boom", status())
        val last = ops().last()
        assertEquals(1, last.payload["content_version"].asInt())
        assertEquals(CONTENT_V1, last.payload["content_blob_hash"].asText())

        // A later cancelled job (most recently finished): parsed.
        parsing()
        db.txBlocking {
            it.execute("insert into app.job (id, kind, payload, status, finished_at) values (?, 'media-parse', ?::jsonb, 'cancelled', now() + interval '2 hours')", Uuid7.generate(), """{"media_id":"$MEDIA"}""")
        }
        assertEquals(1, runner.reconciler.reconcileAll())
        assertEquals("parsed" to null, status())
        assertEquals(0, runner.reconciler.reconcileAll(), "nothing left to reconcile")
    }

    companion object {
        const val MEDIA = "0193a1b2-0007-7000-8000-000000000001"
        const val JPDB_MEDIA = "0193a1b2-0008-7000-8000-000000000001"
        const val OTHER = "0193a1b2-0009-7000-8000-0000000000ff"
        const val GP = "0193a1b2-0006-7000-8000-000000000001"
        const val OTHER_SEG = "0193a1b2-0009-7000-8000-0000000005a0"
        const val SEG0 ="0193a1b2-0007-7000-8000-0000000005a0"
        const val SEG1 = "0193a1b2-0007-7000-8000-0000000005a1"
        const val SEG1_V2 = "0193a1b2-0007-7000-8000-0000000005a2"
        const val SENT_B0 = "0193a1b2-0007-7000-8000-0000000005b0"
        const val SENT_B1 = "0193a1b2-0007-7000-8000-0000000005b1"
        const val SENT_B2 = "0193a1b2-0007-7000-8000-0000000005b2"
        const val SENT_B3 = "0193a1b2-0007-7000-8000-0000000005b3"
        const val CONTENT_V1 = "9587585c69222de449f9a53376cfb763b6b96091f25848ba09ba0ef5852c1af7"
        const val DECK_V1 = "4242e9e7841d43c8ffa92e8431ffaba5d3d7cef96ad9f5c4167ffdac17365fad"
    }
}
