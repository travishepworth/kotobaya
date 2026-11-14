package kotobaya.decks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.blobs.InMemoryBlobStore
import kotobaya.contracts.sync.OpType
import kotobaya.media.publish.Archives
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.SyncFixture
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JLPT-1b: the `deck-publish` runner (VOCAB_LISTS.md 5.2) against a real Postgres, on the worked example of 6. */
class DeckPublishRunnerTest : SyncFixture() {
    private val store = InMemoryBlobStore()
    private val runner by lazy { DeckPublishRunner(db, sync.opApply, store, instanceId = "test") }

    // ------------------------------------------------------------------ fixture helpers

    private fun setup(listKey: String = "jlpt-n5"): String {
        db.txBlocking { VocabListFixture.load(it) }
        val id = Uuid7.generate().toString()
        emit(OpType.DECK_CREATED, """{"id":"$id","name":"JLPT N5","type":"list","list_key":"$listKey"}""")
        return id
    }

    private fun archive(deck: String, version: Int, seqs: List<Long>, positions: List<Long> = seqs.indices.map { 1024L * (it + 1) }, type: String = "list"): ByteArray {
        val head = json.createObjectNode().put("record", "header").put("format", "kotobaya-deck-items").put("format_version", 1)
            .put("deck_id", deck).put("deck_type", type).put("content_version", version)
        head.putObject("counts").put("item", seqs.size)
        val items = seqs.zip(positions).map { (s, p) -> json.createObjectNode().put("record", "item").put("entry_seq", s).put("position", p).put("occurrence_count", 0) }
        return Archives.archive(listOf(head) + items)
    }

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

    private fun parent(deck: String, status: String = "running"): UUID {
        val id = Uuid7.generate()
        val finished = if (status in setOf("succeeded", "failed", "cancelled")) "now()" else "null"
        db.txBlocking {
            it.execute(
                "insert into app.job (id, kind, payload, status, started_at, finished_at, dedupe_key) values (?, 'deck-build', ?::jsonb, ?, now(), $finished, ?)",
                id, """{"deck_id":"$deck"}""", status, "deck-build:$deck:$id",
            )
        }
        return id
    }

    private fun payload(deck: String, base: Int, bytes: ByteArray, parent: UUID, listKey: String = "jlpt-n5"): ObjectNode =
        json.createObjectNode().put("format_version", 1).put("parent_job_id", parent.toString()).put("deck_id", deck)
            .put("list_key", listKey).put("base_content_version", base).put("unmatched", 0)
            .also { it.set<JsonNode>("deck_items", blob(bytes)) }

    /** Inserts the child as the worker does (5.1) and returns its id. */
    private fun child(payload: ObjectNode): UUID {
        val id = Uuid7.generate()
        db.txBlocking {
            it.execute(
                "insert into app.job (id, kind, payload, dedupe_key, max_attempts) values (?, 'deck-publish', ?::jsonb, ?, 1)",
                id, json.writeValueAsString(payload), "deck-publish:" + payload["parent_job_id"].asText(),
            )
        }
        return id
    }

    private fun job(id: UUID): Map<String, Any?> = db.txBlocking {
        it.fetchOne("select status, error, result::text as result, progress, progress_message from app.job where id = ?", id)!!.intoMap()
    }

    private fun assertJob(status: String, id: UUID, error: String? = null) {
        val j = job(id)
        assertEquals(status, j["status"], "job $id: $j")
        if (error != null) assertTrue((j["error"] as String?)?.startsWith("$error: ") == true, "error ${j["error"]} should start with $error")
    }

    private fun published(): List<JsonNode> = db.txBlocking { dsl ->
        dsl.fetch("select payload::text from app.change where op_type = 'deck.content_published' order by seq").map { json.readTree(it.get(0, String::class.java)) }
    }

    private fun items(deck: String): List<Pair<Long, Long>> = db.txBlocking { dsl ->
        dsl.fetch(
            "select i.entry_seq, i.position from app.deck_item i join app.deck d on d.id = i.deck_id " +
                "where i.deck_id = ?::uuid and i.item_key = 'e:' || i.entry_seq and i.note_id is null and i.occurrence_count = 0 " +
                "and i.added_at = d.created_at and i.deleted_at is null and i.hlc is null order by i.position",
            deck,
        ).map { it.get(0, Long::class.java) to it.get(1, Long::class.java) }
    }

    private fun publish(deck: String, base: Int, seqs: List<Long>, parent: UUID = parent(deck)): Pair<UUID, String> {
        val bytes = archive(deck, base + 1, seqs)
        val c = child(payload(deck, base, bytes, parent))
        assertTrue(runner.runOnce())
        return c to Archives.sha256(bytes)
    }

    // ------------------------------------------------------------------ the worked example

    @Test
    fun workedExampleInstallsTheArchiveAndMintsOneDeckContentPublished() {
        val deck = setup()
        val parent = parent(deck)
        val (c, hash) = publish(deck, 0, VocabListFixture.N5_ORDER, parent)
        assertJob("succeeded", c)

        assertEquals(VocabListFixture.N5_ORDER.mapIndexed { i, s -> s to 1024L * (i + 1) }, items(deck))
        val ops = published()
        assertEquals(1, ops.size)
        assertEquals(json.readTree("""{"deck_id":"$deck","content_version":1,"content_blob_hash":"$hash"}"""), ops.single())
        val row = row("deck", "id" to deck)!!
        assertEquals(1, row["content_version"].asInt())
        assertEquals(hash, row["content_blob_hash"].asText())

        assertEquals(
            json.readTree("""{"deck_id":"$deck","content_version":1,"items":5,"list_key":"jlpt-n5","changed":true,"unmatched":0}"""),
            json.readTree(job(c)["result"] as String),
        )
        assertEquals("publishing", job(parent)["progress_message"])
        assertEquals(0.95f, job(parent)["progress"] as Float, 0.0001f)
    }

    @Test
    fun reRunningTheSameChildIsIdempotent() {
        val deck = setup()
        val (c, _) = publish(deck, 0, VocabListFixture.N5_ORDER)
        val rows = items(deck)
        val head = count("select max(seq) from app.change")
        assertFalse(runner.runOnce(), "nothing left to claim")

        // A re-claim after the effects committed (e.g. a stale re-queue): changes nothing, succeeds again.
        db.txBlocking { it.execute("update app.job set status = 'queued', locked_by = null, finished_at = null where id = ?", c) }
        assertTrue(runner.runOnce())
        assertJob("succeeded", c)
        assertEquals(head, count("select max(seq) from app.change"), "no second op")
        assertEquals(1, published().size)
        assertEquals(rows, items(deck))
        assertEquals(5L, count("select count(*) from app.deck_item where deck_id = ?::uuid", deck))
    }

    @Test
    fun aStaleOrFutureBaseFailsStaleBaseAndInstallsNothing() {
        val deck = setup()
        publish(deck, 0, VocabListFixture.N5_ORDER)
        val rows = items(deck)
        val head = count("select max(seq) from app.change")

        // A second build planned on v0 (it raced the first): stale_base, its worker re-plans.
        val (late, _) = publish(deck, 0, listOf(1579840L))
        assertJob("failed", late, "stale_base")
        // A child claiming a base the deck never reached (out of order): stale_base too.
        val (ahead, _) = publish(deck, 2, listOf(1579840L))
        assertJob("failed", ahead, "stale_base")
        assertEquals(head, count("select max(seq) from app.change"))
        assertEquals(rows, items(deck))

        // The re-planned build on v1 replaces the items wholesale and announces v2.
        val (next, hash) = publish(deck, 1, listOf(1358280L, 1579840L))
        assertJob("succeeded", next)
        assertEquals(listOf(1358280L to 1024L, 1579840L to 2048L), items(deck))
        assertEquals(listOf(1, 2), published().map { it["content_version"].asInt() })
        assertEquals(hash, published().last()["content_blob_hash"].asText())
        assertEquals(2, row("deck", "id" to deck)!!["content_version"].asInt())
    }

    // ------------------------------------------------------------------ failures (5.2)

    @Test
    fun eachCheckOfFiveTwoFailsWithItsCodeAndChangesNothing() {
        val deck = setup()
        val head = count("select coalesce(max(seq), 0) from app.change")
        val good = VocabListFixture.N5_ORDER

        fun fails(code: String, payload: ObjectNode) {
            val c = child(payload)
            assertTrue(runner.runOnce())
            assertJob("failed", c, code)
        }
        fails("unsupported_format", payload(deck, 0, archive(deck, 1, good), parent(deck)).put("format_version", 2))
        fails("invalid_payload", payload(deck, 0, archive(deck, 1, good), parent(deck)).put("extra", 1))
        fails("invalid_payload", payload(deck, 0, archive(deck, 1, good), parent(deck), listKey = "jlpt-n4"))
        fails("blob_missing", payload(deck, 0, archive(deck, 1, good), parent(deck)).also { (it["deck_items"] as ObjectNode).put("blob_hash", "0".repeat(64)) })
        fails("invalid_snapshot", payload(deck, 0, archive(deck, 1, good, type = "media", positions = (1L..5L).toList()), parent(deck)))
        fails("invalid_snapshot", payload(deck, 0, archive(deck, 1, good, positions = listOf(1024L, 2048L, 3072L, 4096L, 6144L)), parent(deck)))
        fails("invalid_snapshot", payload(deck, 0, archive(deck, 1, emptyList()), parent(deck)))
        fails("invalid_snapshot", payload(deck, 0, archive(deck, 2, good), parent(deck)))
        fails("invalid_snapshot", payload(deck, 0, archive(UUID.randomUUID().toString(), 1, good), parent(deck)))
        fails("invalid_snapshot", payload(deck, 0, archive(deck, 1, listOf(1579840L, 4242424L)), parent(deck)))
        val other = UUID.randomUUID().toString()
        fails("unknown_deck", payload(other, 0, archive(other, 1, good), parent(other)))

        // Parent no longer running: the child is cancelled.
        val cancelled = child(payload(deck, 0, archive(deck, 1, good), parent(deck, status = "failed")))
        assertTrue(runner.runOnce())
        assertJob("cancelled", cancelled)

        assertEquals(head, count("select coalesce(max(seq), 0) from app.change"), "no op minted by a failed child")
        assertEquals(0L, count("select count(*) from app.deck_item where deck_id = ?::uuid", deck))

        // A deck deleted while its build was queued: deck_deleted, nothing installed.
        emit(OpType.DECK_DELETED, """{"id":"$deck"}""")
        fails("deck_deleted", payload(deck, 0, archive(deck, 1, good), parent(deck)))
        assertEquals(0L, count("select count(*) from app.deck_item where deck_id = ?::uuid", deck))
        assertEquals(0, published().size)
    }

    @Test
    fun aNonListDeckIsInvalidPayload() {
        db.txBlocking { VocabListFixture.load(it) }
        val user = Uuid7.generate().toString()
        emit(OpType.DECK_CREATED, """{"id":"$user","name":"Inbox","type":"user"}""")
        val c = child(payload(user, 0, archive(user, 1, VocabListFixture.N5_ORDER), parent(user)))
        assertTrue(runner.runOnce())
        assertJob("failed", c, "invalid_payload")
    }
}
