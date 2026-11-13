package kotobaya.media.api

import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.jobs.JobRepo
import kotobaya.server.Uuid7
import kotobaya.sync.SyncFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** T411b: media API over HTTP; jobs only enqueued, learning-data changes only as ops. */
class MediaApiTest : SyncFixture() {
    private val module by lazy { MediaApiModule(db, sync.opApply) }
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    private fun mediaApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = module.handlers, block = block)

    private fun importer() = mint(TokenKind.PERSONAL, listOf("read", "write", "import")).second

    private fun blob(hash: String, kind: String = "upload") = db.txBlocking {
        it.execute("insert into app.blob (hash, size_bytes, content_type, kind) values (?, 1, 'application/octet-stream', ?) on conflict do nothing", hash, kind)
    }

    private fun media(id: String, source: String = "file", jpdbId: String? = null, status: String = "parsed", title: String = "T $id") = db.txBlocking {
        it.execute(
            "insert into app.media (id, title, kind, source, jpdb_id, parse_status, content_version, content_blob_hash, created_at, updated_at) " +
                "values (?::uuid, ?, 'anime', ?, ?, ?, 1, repeat('c', 64), now(), now())",
            id, title, source, jpdbId, status,
        )
        it.execute(
            "insert into app.deck (id, name, type, media_id, created_at, updated_at) values (?::uuid, ?, 'media', ?::uuid, now(), now())",
            id, title, id,
        )
    }

    private fun ops(type: String): Long = count("select count(*) from app.change where op_type = ?", type)
    private fun jobs(kind: String): Long = count("select count(*) from app.job where kind = ?", kind)
    private fun id() = Uuid7.generate().toString()

    private val importFile = { h: String -> mapOf("source" to "file", "files" to listOf(mapOf("blob_hash" to h, "filename" to "ep.srt"))) }

    @Test
    fun importMediaRetryConcurrentMissingBlobAndWrongSource() = mediaApp {
        val t = importer()
        blob(hashA)
        val importId = id()
        val body = importFile(hashA) + mapOf("import_id" to importId, "title" to "Show", "kind" to "anime")
        val first = client.call(HttpMethod.Post, "/media/import", bearer = t, body = body)
        assertEquals(202, first.status.value, first.json().toString())
        assertEquals(importId, first.json()["id"].asText())
        assertEquals(importId, first.json()["payload"]["media_id"].asText())
        assertEquals("media-import", first.json()["kind"].asText())
        assertEquals(0, ops("media.content_published"), "new media: no op")

        // retry, even with a different body: same job, nothing enqueued
        val retry = client.call(HttpMethod.Post, "/media/import", bearer = t, body = mapOf("import_id" to importId, "source" to "paste"))
        assertEquals(200, retry.status.value)
        assertEquals(importId, retry.json()["id"].asText())
        assertEquals(1, jobs("media-import"))

        // the job row survives retention (kept forever), so the retry still resolves
        db.txBlocking { it.execute("update app.job set status = 'succeeded', finished_at = now() - interval '400 days' where id = ?::uuid", importId) }
        db.txBlocking { JobRepo.prune(it) }
        assertEquals(200, client.call(HttpMethod.Post, "/media/import", bearer = t, body = body).status.value)

        // import_id of a job of another kind
        val other = id()
        db.txBlocking { JobRepo.enqueue(it, "media-parse", kotobaya.server.json.createObjectNode().put("media_id", id()), id = java.util.UUID.fromString(other)) }
        val clash = client.call(HttpMethod.Post, "/media/import", bearer = t, body = body + mapOf("import_id" to other))
        assertProblem(clash, 409, "already_exists")
        assertEquals(other, clash.json()["existing_id"].asText())

        // concurrent import into one media
        val m = id()
        media(m)
        val append = mapOf("media_id" to m) + importFile(hashA)
        val a1 = client.call(HttpMethod.Post, "/media/import", bearer = t, body = append)
        assertEquals(202, a1.status.value, a1.json().toString())
        assertEquals(1, ops("media.content_published"), "append emits parsing")
        val a2 = client.call(HttpMethod.Post, "/media/import", bearer = t, body = append)
        assertProblem(a2, 409, "already_exists")
        assertEquals(a1.json()["id"].asText(), a2.json()["existing_id"].asText())

        // missing blob names the element
        val missing = client.call(HttpMethod.Post, "/media/import", bearer = t, body = mapOf("title" to "x", "kind" to "web") + importFile(hashB))
        assertProblem(missing, 422, "validation_failed")
        assertEquals("/files/0/blob_hash", missing.json()["errors"][0]["pointer"].asText())

        // append to wrong-source media
        val p = id()
        media(p, source = "paste")
        val wrong = client.call(HttpMethod.Post, "/media/import", bearer = t, body = mapOf("media_id" to p) + importFile(hashA))
        assertProblem(wrong, 422, "validation_failed")
        assertEquals("/source", wrong.json()["errors"][0]["pointer"].asText())
        assertEquals(0, count("select count(*) from app.job where payload->>'media_id' = ?", p))
    }

    @Test
    fun importJpdbDeckReusesLiveMediaAndDedupes() = mediaApp {
        val t = importer()
        val m = id()
        media(m, source = "jpdb", jpdbId = "anime/5370")
        val r = client.call(HttpMethod.Post, "/media/import/jpdb", bearer = t, body = mapOf("jpdb_id" to "anime/5370"))
        assertEquals(202, r.status.value, r.json().toString())
        assertEquals(m, r.json()["payload"]["media_id"].asText())
        assertEquals(1, ops("media.content_published"))
        val again = client.call(HttpMethod.Post, "/media/import/jpdb", bearer = t, body = mapOf("jpdb_id" to "anime/5370"))
        assertEquals(200, again.status.value)
        assertEquals(r.json()["id"].asText(), again.json()["id"].asText())
        assertEquals(1, ops("media.content_published"))
        val fresh = client.call(HttpMethod.Post, "/media/import/jpdb", bearer = t, body = mapOf("jpdb_id" to "anime/1"))
        assertEquals(202, fresh.status.value)
        assertNotEquals(m, fresh.json()["payload"]["media_id"].asText())
        assertProblem(client.call(HttpMethod.Post, "/media/import/jpdb", bearer = t, body = mapOf("jpdb_id" to "anime/2", "remap_only" to true)), 422, "validation_failed")
    }

    @Test
    fun deleteMediaEmitsOpsAndLeavesContentGoneButManualSpansAndNotes() = mediaApp {
        val t = importer()
        val m = id()
        media(m)
        val seg = id()
        val sen = id()
        val gp = id()
        db.txBlocking {
            it.execute("insert into app.media_segment (id, media_id, ordinal, kind) values (?::uuid, ?::uuid, 0, 'whole')", seg, m)
            it.execute("insert into app.sentence (id, media_id, segment_id, ordinal, text) values (?::uuid, ?::uuid, ?::uuid, 0, 'あ')", sen, m, seg)
            it.execute("insert into app.occurrence (segment_id, entry_seq, media_id, count) values (?::uuid, 1, ?::uuid, 1)", seg, m)
            it.execute("insert into app.grammar_point (id, title, created_at, updated_at) values (?::uuid, 'g', now(), now())", gp)
            it.execute("insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, media_id) values (?::uuid, ?::uuid, 0, 1, 'detected', ?::uuid)", sen, gp, m)
            it.execute(
                "insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, hlc) values (?::uuid, ?::uuid, 0, 1, 'manual', '0193a1b2c100-0000-00000000-0000-7000-8000-000000000001')",
                sen, gp,
            )
        }
        db.txBlocking { JobRepo.enqueue(it, "media-parse", kotobaya.server.json.createObjectNode().put("media_id", m), dedupeKey = "media-parse:$m") }
        val notes = count("select count(*) from app.note")
        val r = client.call(HttpMethod.Delete, "/media/$m", bearer = t)
        assertEquals(204, r.status.value)
        assertTrue(r.headers["Kotobaya-Seq"] != null)
        assertEquals(1, ops("media.deleted"))
        assertEquals(1, ops("deck.deleted"))
        for (tbl in listOf("media_segment", "sentence", "occurrence")) assertEquals(0, count("select count(*) from app.$tbl where media_id = ?::uuid", m), tbl)
        assertEquals(0, count("select count(*) from app.sentence_grammar_span where origin = 'detected'"))
        assertEquals(1, count("select count(*) from app.sentence_grammar_span where origin = 'manual'"))
        assertEquals(notes, count("select count(*) from app.note"))
        assertEquals("cancelled", db.txBlocking { it.fetchValue("select status from app.job where payload->>'media_id' = ?", m) as String })
        // already deleted: 204, no op
        assertEquals(204, client.call(HttpMethod.Delete, "/media/$m", bearer = t).status.value)
        assertEquals(1, ops("media.deleted"))
        assertEquals(410, client.call(HttpMethod.Get, "/media/$m", bearer = t).status.value)
        assertEquals(0, client.call(HttpMethod.Get, "/media?q=T", bearer = t).json()["items"].size())
    }

    @Test
    fun reparseJpdbIs409AndFileMediaEmitsParsingOnce() = mediaApp {
        val t = importer()
        val j = id()
        media(j, source = "jpdb", jpdbId = "anime/9")
        assertProblem(client.call(HttpMethod.Post, "/media/$j/reparse", bearer = t), 409, "not_applicable")
        assertEquals(0, jobs("media-parse"))
        val f = id()
        media(f)
        val a = client.call(HttpMethod.Post, "/media/$f/reparse", bearer = t)
        assertEquals(202, a.status.value)
        assertEquals(1, ops("media.content_published"))
        val b = client.call(HttpMethod.Post, "/media/$f/reparse", bearer = t)
        assertEquals(200, b.status.value)
        assertEquals(a.json()["id"].asText(), b.json()["id"].asText())
        assertEquals(1, ops("media.content_published"))
        assertEquals("parsing", db.txBlocking { it.fetchValue("select parse_status from app.media where id = ?::uuid", f) as String })
    }

    @Test
    fun updateMediaEmitsOneOpAndChecksTheCover() = mediaApp {
        val t = importer()
        val m = id()
        media(m)
        blob(hashA, "upload")
        assertProblem(client.call(HttpMethod.Patch, "/media/$m", bearer = t, body = mapOf("cover_blob_hash" to hashA)), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Patch, "/media/$m", bearer = t, body = emptyMap<String, Any>()), 422, "validation_failed")
        blob(hashB, "image")
        val r = client.call(HttpMethod.Patch, "/media/$m", bearer = t, body = mapOf("title" to "New", "cover_blob_hash" to hashB))
        assertEquals(200, r.status.value, r.json().toString())
        assertEquals("New", r.json()["title"].asText())
        assertEquals(hashB, r.json()["cover_blob_hash"].asText())
        assertEquals(1, ops("media.updated"))
    }

    private fun dictEntry(seq: Long, retired: Boolean = false) = db.txBlocking {
        it.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
        it.execute(
            "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id, retired_release_id) " +
                "values (?, '魔族', 'まぞく', '[{\"text\":\"まぞく\"}]', '[{\"gloss\":[{\"text\":\"demon\"}]}]', 1, ?) on conflict do nothing",
            seq, if (retired) 1 else null,
        )
    }

    @Test
    fun mappingCorrectionsPutListClear() = mediaApp {
        val t = importer()
        val m1 = id()
        val m2 = id()
        val gone = id()
        media(m1, source = "jpdb", jpdbId = "anime/1")
        media(m2, source = "jpdb", jpdbId = "anime/2")
        media(gone, source = "jpdb", jpdbId = "anime/3")
        db.txBlocking {
            it.execute("insert into app.jpdb_vocab (jpdb_vid, spelling, reading, first_seen_at) values (9300001, 'a', 'a', now()), (9300003, '魔族', 'まぞく', now())")
            it.execute("insert into app.jpdb_vocab_media values (9300001, ?::uuid, 312), (9300003, ?::uuid, 75), (9300003, ?::uuid, 5), (9300003, ?::uuid, 1)", m1, m1, m2, gone)
            it.execute("update app.media set deleted_at = now() where id = ?::uuid", gone)
        }
        dictEntry(1612400)
        dictEntry(1612401, retired = true)
        val list = client.call(HttpMethod.Get, "/import/jpdb/vocab", bearer = t).json()
        assertEquals(listOf(9300001L, 9300003L), list["items"].map { it["jpdb_vid"].asLong() })
        assertEquals(80, list["items"][1]["occurrence_total"].asInt())
        assertEquals(2, list["items"][1]["media"].size())

        val url = "/import/jpdb/vocab/9300003/resolution"
        assertProblem(client.call(HttpMethod.Put, url, bearer = t, body = mapOf("resolution" to "mapped")), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Put, url, bearer = t, body = mapOf("resolution" to "ignored", "entry_seq" to 1612400)), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Put, url, bearer = t, body = mapOf("resolution" to "mapped", "entry_seq" to 1612401)), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Put, "/import/jpdb/vocab/42/resolution", bearer = t, body = mapOf("resolution" to "ignored")), 404, "not_found")
        assertEquals(0, jobs("jpdb-deck-import"))

        val put = client.call(HttpMethod.Put, url, bearer = t, body = mapOf("resolution" to "mapped", "entry_seq" to 1612400))
        assertEquals(200, put.status.value, put.json().toString())
        assertEquals("mapped", put.json()["vocab"]["resolution"].asText())
        assertEquals(2, put.json()["jobs"].size(), "live jpdb media only (the deleted one is skipped)")
        assertTrue(put.json()["jobs"].all { it["payload"]["remap_only"].asBoolean() && it["kind"].asText() == "jpdb-deck-import" })
        assertEquals(2, ops("media.content_published"))
        assertEquals(2, count("select count(*) from app.job where dedupe_key like 'jpdb-deck-remap:%'"))

        // same again: nothing written, nothing enqueued
        val same = client.call(HttpMethod.Put, url, bearer = t, body = mapOf("resolution" to "mapped", "entry_seq" to 1612400))
        assertEquals(0, same.json()["jobs"].size())
        assertEquals(2, ops("media.content_published"))

        // queued remaps are reused and listed; media already parsing emits nothing
        val ign = client.call(HttpMethod.Put, url, bearer = t, body = mapOf("resolution" to "ignored"))
        assertEquals(2, ign.json()["jobs"].size())
        assertEquals(2, jobs("jpdb-deck-import"))
        assertEquals(2, ops("media.content_published"))

        // mapped / ignored rows leave the default queue; status=all keeps them
        val queue = client.call(HttpMethod.Get, "/import/jpdb/vocab", bearer = t).json()
        assertEquals(listOf(9300001L), queue["items"].map { it["jpdb_vid"].asLong() })
        assertEquals(2, client.call(HttpMethod.Get, "/import/jpdb/vocab?status=all", bearer = t).json()["items"].size())
        assertEquals(1, client.call(HttpMethod.Get, "/import/jpdb/vocab?status=ignored&limit=1", bearer = t).json()["items"].size())

        val clear = client.call(HttpMethod.Delete, url, bearer = t)
        assertEquals(200, clear.status.value)
        assertEquals("unresolved", clear.json()["vocab"]["resolution"].asText())
        assertTrue(clear.json()["vocab"]["resolved_at"].isNull)
        assertEquals(0, client.call(HttpMethod.Delete, url, bearer = t).json()["jobs"].size())
        assertProblem(client.call(HttpMethod.Delete, "/import/jpdb/vocab/42/resolution", bearer = t), 404, "not_found")
    }
}
