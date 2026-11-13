package kotobaya.export

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.AuthFixture
import kotobaya.auth.TokenKind
import kotobaya.blobs.InMemoryBlobStore
import kotobaya.server.json
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T608: the JSON dump is complete (nothing silently dropped) and the .apkg opens with the right counts. */
class ExportApiTest : AuthFixture() {
    private val store = InMemoryBlobStore()
    private val module by lazy { ExportModule(db, store) }
    private val vocab = "00000000-0000-7000-8000-000000000101"
    private val server = "00000000-0000-7000-8000-000000000001"
    private val hlc = "000000000001-0000-00000000-0000-7000-8000-000000000001"
    private val audioHash = "a".repeat(64)
    private val imageHash = "b".repeat(64)

    private fun exportApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = module.handlers, block = block)

    private fun reader() = mint(TokenKind.PERSONAL, listOf("read")).second

    private fun sql(q: String, vararg args: Any?) = db.txBlocking { it.execute(q, *args) }

    private fun note(id: String, expression: String, tags: String = "{}", extra: String = "", fields: String = "{\"expression\":\"$expression\"}") = sql(
        "insert into app.note (id, note_type_id, type, fields, tags, created_by_device_id, created_at, updated_at $extra) " +
            "values (?::uuid, ?::uuid, 'vocab', ?::jsonb, ?::text[], ?::uuid, now(), now() ${if (extra.isEmpty()) "" else ", now()"})",
        id, vocab, fields, tags, server,
    )

    private fun card(id: String, noteId: String, kind: String, state: String = "new", suspended: Boolean = false) = sql(
        if (state == "new") "insert into app.card (id, note_id, kind, suspended, created_at, updated_at) values (?::uuid, ?::uuid, ?, ?, now(), now())"
        else "insert into app.card (id, note_id, kind, suspended, state, stability, difficulty, due_at, interval_days, reps, created_at, updated_at) " +
            "values (?::uuid, ?::uuid, ?, ?, 'review', 5, 5, now() + interval '5 days', 5, 1, now(), now())",
        id, noteId, kind, suspended,
    )

    private fun review(id: String, cardId: String, type: String, rating: Int?, undone: Boolean = false) = sql(
        "insert into app.review_log (id, card_id, device_id, hlc, reviewed_at, review_type, rating, manual_action, undone_at, undone_hlc) " +
            "values (?::uuid, ?::uuid, ?::uuid, ?, now(), ?, ?, ?, ${if (undone) "now(), ?" else "null, ?"}::text)",
        id, cardId, server, hlc, type, rating, if (type == "manual") "reset" else null, if (undone) hlc else null,
    )

    private fun uuid(n: Int) = "01931f40-0000-7000-8000-%012d".format(n)

    private fun seed() {
        val live = uuid(1)
        val dead = uuid(2)
        val loser = uuid(3)
        val other = uuid(4)
        note(live, "犬", tags = "{x,pets}", fields = "{\"expression\":\"犬\",\"audio\":\"[sound:$audioHash]\",\"image\":\"<img src=\\\"blob:$imageHash\\\">\"}")
        note(other, "猫")
        note(dead, "鳥", extra = ", deleted_at")
        note(loser, "魚", extra = ", deleted_at")
        sql("update app.note set merged_into_id = ?::uuid where id = ?::uuid", live, loser)
        card(uuid(11), live, "meaning")
        card(uuid(12), live, "reading", state = "review")
        card(uuid(13), other, "meaning", suspended = true)
        card(uuid(14), dead, "meaning")
        sql("update app.card set deleted_at = now() where id = ?::uuid", uuid(14))
        card(uuid(15), loser, "meaning")
        sql("update app.card set deleted_at = now(), merged_into_id = ?::uuid where id = ?::uuid", uuid(11), uuid(15))
        review(uuid(21), uuid(12), "scheduled", 3)
        review(uuid(22), uuid(12), "learning", 1)
        review(uuid(23), uuid(12), "drill", 3)
        review(uuid(24), uuid(12), "cram", 2)
        review(uuid(25), uuid(12), "manual", null)
        review(uuid(26), uuid(12), "scheduled", 4, undone = true)
        sql("insert into app.blob (hash, size_bytes, content_type, kind) values (?, 3, 'audio/mpeg', 'audio'), (?, 4, 'image/png', 'image')", audioHash, imageHash)
        store.objects[audioHash] = byteArrayOf(1, 2, 3)
        store.objects[imageHash] = byteArrayOf(9, 8, 7, 6)
        sql("insert into app.settings (key, value, hlc, updated_at) values ('scheduler.timezone', '\"Asia/Tokyo\"', ?, now())", hlc)
        val media = uuid(31)
        sql("insert into app.media (id, title, kind, source, created_at, updated_at) values (?::uuid, 'Frieren', 'anime', 'file', now(), now())", media)
        sql("insert into app.media_segment (id, media_id, ordinal, kind) values (?::uuid, ?::uuid, 0, 'whole')", uuid(32), media)
        sql(
            "insert into app.sentence (id, media_id, segment_id, ordinal, text) values (?::uuid, ?::uuid, ?::uuid, 0, 'こんにちは')",
            uuid(33), media, uuid(32),
        )
        sql("insert into app.grammar_point (id, title, created_at, updated_at) values (?::uuid, 'te-form', now(), now())", uuid(41))
        sql(
            "insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, media_id) " +
                "values (?::uuid, ?::uuid, 0, 2, 'detected', ?::uuid)",
            uuid(33), uuid(41), media,
        )
        sql("insert into app.parse_override (id, match_text, tokens, created_at, updated_at, hlc) values (?::uuid, 'x', '[{\"a\":1}]', now(), now(), ?)", uuid(51), hlc)
    }

    private fun count(table: String, where: String = "true") = db.txBlocking { it.fetchValue("select count(*) from app.$table where $where") as Long }.toInt()

    private suspend fun ApplicationTestBuilder.get(path: String): HttpResponse = client.call(HttpMethod.Get, path, bearer = reader())

    @Test
    fun jsonDumpHasEveryRowOfEveryTableTombstonesAndLosersIncluded() = exportApp {
        seed()
        val r = get("/export")
        assertEquals(200, r.status.value)
        assertTrue(r.headers["Content-Disposition"]!!.matches(Regex("attachment; filename=\"kotobaya-export-\\d{4}-\\d{2}-\\d{2}\\.json\"")))
        val dump = r.json()
        assertEquals(1, dump["format_version"].asInt())
        assertEquals(count("change").toLong(), dump["high_water_seq"].asLong())
        val tables = dump["tables"]
        val expected = mapOf(
            "note_type" to count("note_type"), "note" to 4, "card" to 5, "review_log" to 6, "word_status" to count("word_status"),
            "deck" to count("deck"), "deck_item" to count("deck_item"), "confusable" to count("confusable"),
            "grammar_point" to 1, "sentence_grammar_span" to 0, "settings" to count("settings"), "fsrs_params" to count("fsrs_params"),
            "media" to 1, "parse_override" to 1,
        )
        for ((name, n) in expected) assertEquals(n, tables[name]!!.size(), "rows of $name")
        assertEquals(count("note"), tables["note"].size())
        assertEquals(count("card"), tables["card"].size())
        assertEquals(count("review_log"), tables["review_log"].size())
        // tombstones and merge losers are in the dump; the generated column is not
        val notes = tables["note"].associateBy { it["id"].asText() }
        assertTrue(!notes.getValue(uuid(2))["deleted_at"].isNull)
        assertEquals(uuid(1), notes.getValue(uuid(3))["merged_into_id"].asText())
        assertTrue(!notes.getValue(uuid(1)).has("search_text"))
        assertEquals(listOf("x", "pets"), notes.getValue(uuid(1))["tags"].map { it.asText() })
        assertTrue(notes.getValue(uuid(1))["created_at"].asText().endsWith("+00:00") || notes.getValue(uuid(1))["created_at"].asText().endsWith("Z"))
        assertTrue(tables["settings"].any { it["key"].asText() == "scheduler.timezone" && it["hlc"].asText() == hlc })
        assertEquals(setOf("note_type", "note", "card", "review_log", "word_status", "deck", "deck_item", "confusable", "grammar_point", "sentence_grammar_span", "settings", "fsrs_params", "media", "parse_override"), tables.fieldNames().asSequence().toSet())
        for (excluded in listOf("device", "api_token", "change", "job", "blob")) assertTrue(!tables.has(excluded), excluded)
        assertTrue(!tables.has("media_segment") && !tables.has("sentence") && !tables.has("occurrence"))
    }

    @Test
    fun includeMediaContentAddsTheBulkTablesAndDetectedSpans() = exportApp {
        seed()
        val tables = get("/export?include=media_content").json()["tables"]
        assertEquals(1, tables["media_segment"].size())
        assertEquals(1, tables["sentence"].size())
        assertEquals(0, tables["occurrence"].size())
        assertEquals(count("sentence_grammar_span"), tables["sentence_grammar_span"].size())
        assertEquals(1, tables["sentence_grammar_span"].size())
        assertEquals(422, get("/export?include=bogus").status.value)
    }

    private class Apkg(val entries: Map<String, ByteArray>)

    private fun unzip(bytes: ByteArray): Apkg {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return Apkg(out)
    }

    private fun <T> Apkg.sqlite(block: (java.sql.Connection) -> T): T {
        val f = Files.createTempFile("apkg-test-", ".db")
        try {
            Files.write(f, entries.getValue("collection.anki21"))
            return DriverManager.getConnection("jdbc:sqlite:$f").use(block)
        } finally {
            Files.deleteIfExists(f)
        }
    }

    private fun java.sql.Connection.scalar(q: String): Long = createStatement().use { st -> st.executeQuery(q).use { it.next(); it.getLong(1) } }

    private suspend fun ApplicationTestBuilder.apkg(path: String): Apkg {
        val r = get(path)
        assertEquals(200, r.status.value)
        assertTrue(r.headers["Content-Disposition"]!!.matches(Regex("attachment; filename=\"kotobaya-\\d{4}-\\d{2}-\\d{2}\\.apkg\"")))
        return unzip(runBlocking { r.readRawBytes() })
    }

    @Test
    fun apkgHasLiveNotesAndCardsRevlogWithoutTheFourKindsAndTheReferencedMedia() = exportApp {
        seed()
        val pkg = apkg("/export/anki")
        pkg.sqlite { c ->
            assertEquals(2, c.scalar("select count(*) from notes"))
            assertEquals(3, c.scalar("select count(*) from cards"))
            assertEquals(1, c.scalar("select count(*) from cards where queue = -1"))
            assertEquals(1, c.scalar("select count(*) from cards where type = 2 and data like '%\"s\":5.0%'"))
            // only the scheduled and learning rows that are not undone
            assertEquals(2, c.scalar("select count(*) from revlog"))
            assertEquals(1, c.scalar("select count(*) from notes where tags like ' x pets %'"))
            val models = json.readTree(c.createStatement().use { st -> st.executeQuery("select models from col").use { it.next(); it.getString(1) } })
            assertEquals(1, models.size())
            assertEquals(2, models.first()["tmpls"].size().coerceAtMost(2))
        }
        val map = json.readTree(pkg.entries.getValue("media"))
        assertEquals(setOf("$audioHash.mp3", "$imageHash.png"), map.fields().asSequence().map { it.value.asText() }.toSet())
        for ((k, v) in map.fields()) {
            val expected = if (v.asText().endsWith("mp3")) byteArrayOf(1, 2, 3) else byteArrayOf(9, 8, 7, 6)
            assertTrue(expected.contentEquals(pkg.entries.getValue(k)), v.asText())
        }
        pkg.sqlite { c ->
            val flds = c.createStatement().use { st -> st.executeQuery("select flds from notes where guid = '${uuid(1)}'").use { it.next(); it.getString(1) } }
            assertTrue("[sound:$audioHash.mp3]" in flds, flds)
            assertTrue("$imageHash.png" in flds && "blob:" !in flds, flds)
        }
    }

    @Test
    fun apkgQueryFiltersAndNoMatchIsAValidEmptyPackage() = exportApp {
        seed()
        apkg("/export/anki?query=tag:x").sqlite { c ->
            assertEquals(1, c.scalar("select count(*) from notes"))
            assertEquals(2, c.scalar("select count(*) from cards"))
        }
        val empty = apkg("/export/anki?query=tag:nomatchatall")
        empty.sqlite { c ->
            assertEquals(0, c.scalar("select count(*) from notes"))
            assertEquals(0, c.scalar("select count(*) from cards"))
            assertEquals(0, c.scalar("select count(*) from revlog"))
            assertEquals(1, c.scalar("select count(*) from col"))
        }
        assertEquals("{}", String(empty.entries.getValue("media")))
        assertProblem(get("/export/anki?query=" + java.net.URLEncoder.encode("(", "UTF-8")), 422, "invalid_query")
    }
}
