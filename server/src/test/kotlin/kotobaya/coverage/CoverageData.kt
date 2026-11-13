package kotobaya.coverage

import com.fasterxml.jackson.databind.JsonNode
import kotobaya.server.Database
import kotobaya.server.json
import java.io.File

/** Test data for coverage: direct inserts into the media tables (server-built content; no ops involved). */
internal class CoverageData(private val db: Database) {
    fun media(id: String, contentVersion: Int = 1, title: String = "m", deleted: Boolean = false) {
        db.txBlocking {
            it.execute(
                "insert into app.media (id, title, kind, source, parse_status, content_version, created_at, updated_at, deleted_at) " +
                    "values (?::uuid, ?, 'anime', 'file', ?, ?, now(), now(), case when ? then now() end)",
                id, title, if (contentVersion > 0) "parsed" else "pending", contentVersion, deleted,
            )
        }
    }

    fun segment(mediaId: String, id: String, ordinal: Int, title: String, tokenCount: Int, unmapped: Int) {
        db.txBlocking {
            it.execute(
                "insert into app.media_segment (id, media_id, ordinal, kind, title, token_count, unmapped_token_count) " +
                    "values (?::uuid, ?::uuid, ?, 'episode', ?, ?, ?)",
                id, mediaId, ordinal, title, tokenCount, unmapped,
            )
        }
    }

    fun occurrence(mediaId: String, segmentId: String, seq: Long, count: Int, posClass: String = "content") {
        db.txBlocking {
            it.execute(
                "insert into app.occurrence (segment_id, entry_seq, media_id, count, pos_class) values (?::uuid, ?, ?::uuid, ?, ?)",
                segmentId, seq, mediaId, count, posClass,
            )
        }
    }

    /** Sets the effective status; `null` deletes the row (= new). Uses the override column for override-only statuses. */
    fun status(seq: Long, status: String?) {
        db.txBlocking { dsl ->
            dsl.execute("delete from app.word_status where entry_seq = ?", seq)
            if (status == null) return@txBlocking
            val auto = status.takeIf { it in setOf("new", "learning", "known") }
            val override = status.takeIf { auto == null }
            dsl.execute(
                "insert into app.word_status (entry_seq, status, auto_status, override_status, override_hlc, updated_at) " +
                    "values (?, ?, ?, ?, case when ? then '0193a1b2c100-0000-00000000-0000-7000-8000-000000000001' end, now())",
                seq, status, auto, override, override != null,
            )
        }
    }

    fun deck(id: String, type: String, mediaId: String? = null, deleted: Boolean = false) {
        db.txBlocking {
            it.execute(
                "insert into app.deck (id, name, type, media_id, list_key, query, created_at, updated_at, deleted_at) " +
                    "values (?::uuid, 'd', ?, ?::uuid, ?, ?, now(), now(), case when ? then now() end)",
                id, type, mediaId, if (type == "list") "list-$id" else null, if (type == "smart") "tag:x" else null, deleted,
            )
        }
    }

    fun deckItem(deckId: String, seq: Long, occurrenceCount: Int = 0) {
        db.txBlocking {
            it.execute(
                "insert into app.deck_item (deck_id, item_key, entry_seq, position, occurrence_count, added_at) values (?::uuid, ?, ?, ?, ?, now())",
                deckId, "e:$seq", seq, seq, occurrenceCount,
            )
        }
    }

    /** Loads a `fixtures/coverage` deck; returns its JSON. */
    fun load(name: String): JsonNode {
        val f = json.readTree(File(fixtures, name))
        val mediaId = f["media_id"].asText()
        media(mediaId, title = f["name"].asText())
        val segs = f["segments"].toList()
        segs.forEachIndexed { i, s ->
            val mapped = f["occurrences"].sumOf { it["counts"][i].asInt() }
            val unmapped = s["unmapped_token_count"].asInt()
            segment(mediaId, s["id"].asText(), s["ordinal"].asInt(), s["title"].asText(), mapped + unmapped, unmapped)
        }
        for (o in f["occurrences"]) {
            o["counts"].forEachIndexed { i, c ->
                if (c.asInt() > 0) occurrence(mediaId, segs[i]["id"].asText(), o["seq"].asLong(), c.asInt(), o["pos_class"].asText())
            }
        }
        f["word_status"].fields().forEach { (seq, st) -> status(seq.toLong(), st.asText()) }
        return f
    }

    companion object {
        val fixtures: File by lazy {
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { File(it, "fixtures/coverage") }.first { it.isDirectory }
        }
    }
}
