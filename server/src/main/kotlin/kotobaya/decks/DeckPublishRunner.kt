package kotobaya.decks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotobaya.blobs.BlobStore
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.media.publish.DeckHeader
import kotobaya.media.publish.DeckItemRecord
import kotobaya.media.publish.DeckItemSink
import kotobaya.media.publish.PublishBlobs
import kotobaya.media.publish.PublishFailure
import kotobaya.media.publish.SnapshotInvalid
import kotobaya.media.publish.SnapshotReader
import kotobaya.server.Database
import kotobaya.server.json
import kotobaya.sync.OpApply
import kotobaya.sync.OpRefused
import kotobaya.sync.OpTx
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The server half of the `deck-publish` hand-off (contracts/db/VOCAB_LISTS.md 5.2, C-21), the only claimer of that
 * server-internal job kind (SCHEMA.md 4.4). Mirrors [kotobaya.media.publish.MediaPublishRunner] (claim, heartbeat,
 * stale re-queue, blob fetch, error handling) for list decks.
 *
 * Per claimed child: validate the payload; end it `cancelled` if the parent is not running; fetch the deck-item
 * archive, check length and SHA-256, validate it (SNAPSHOT.md 4.2 plus the `validate.py deck-publish` rules) with no
 * lock held; then, in ONE op transaction (advisory lock 7033001, taken by [OpApply]): re-check the claim and the
 * parent, lock and check the deck, replace its `deck_item` rows with the archive (SNAPSHOT.md 7 step 4), mint
 * `deck.content_published` (version base + 1), and end the child `succeeded` with `JobResultDeckBuild`. Any error
 * rolls everything back; the child then ends `failed` with `<code>: <message>` in a new transaction.
 *
 * Idempotent: the child's `succeeded` commits with its effects; a re-claim of a child whose archive is already the
 * deck's published version changes nothing and reports the same result.
 */
class DeckPublishRunner(
    private val db: Database,
    private val opApply: OpApply,
    private val objectStore: BlobStore,
    private val pollMs: Long = 1_000,
    private val staleAfterMs: Long = 300_000,
    private val heartbeatMs: Long = 30_000,
    private val instanceId: String = "server-" + UUID.randomUUID().toString().take(8),
) {
    private val log = LoggerFactory.getLogger("kotobaya.decks.publish")

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val ran = try {
                runOnce()
            } catch (e: Exception) {
                log.warn("deck publish runner: {}", e.message)
                false
            }
            if (!ran) delay(pollMs)
        }
    }

    /** Re-queues stale children, then claims and runs one queued child; false if there was none. */
    fun runOnce(): Boolean {
        val token = "$instanceId:${UUID.randomUUID()}"
        val claimed = db.txBlocking { dsl ->
            dsl.execute(
                "update app.job set status = 'queued', locked_by = null where kind = ? and status = 'running' " +
                    "and coalesce(heartbeat_at, started_at, created_at) < now() - make_interval(secs => ?)",
                KIND, staleAfterMs / 1000.0,
            )
            dsl.fetchOptional(
                "update app.job set status = 'running', locked_by = ?, attempts = attempts + 1, started_at = now(), heartbeat_at = now() " +
                    "where id = (select id from app.job where kind = ? and status = 'queued' and run_after <= now() " +
                    "order by run_after, created_at for update skip locked limit 1) returning id, payload::text",
                token, KIND,
            ).map { it.get(0, UUID::class.java) to json.readTree(it.get(1, String::class.java)) }.orElse(null)
        } ?: return false
        val (id, payload) = claimed
        val beat = startHeartbeat(id, token)
        try {
            run(id, token, payload)
        } finally {
            beat.stop()
        }
        return true
    }

    /** The claim moved to another runner (stale re-queue); roll back and leave the child alone. */
    private class LostClaim : RuntimeException("claim lost")

    /** The validated archive: header and items in archive order. */
    private class Archive(val header: DeckHeader, val items: List<DeckItemRecord>)

    private fun run(id: UUID, token: String, raw: JsonNode) {
        val files = mutableListOf<Path>()
        try {
            val payload = DeckPublishPayload.parse(raw)
            if (parentStatus(payload.parentJobId) != "running") {
                end(id, token, "cancelled")
                return
            }
            val file = PublishBlobs.fetch(db, objectStore, payload.deckItems, files, "deck-publish-")
            val archive = read(file)
            check(payload, archive)
            val result = json.createObjectNode().apply {
                put("deck_id", payload.deckId)
                put("content_version", payload.version)
                put("items", archive.items.size)
                put("list_key", payload.listKey)
                put("changed", true)
                put("unmatched", payload.unmatched)
            }
            val outcome = opApply.transactionBlocking(null) { tx -> publish(tx, id, token, payload, archive, result) }
            log.info("deck publish {} for deck {}: {}", id, payload.deckId, outcome)
        } catch (e: LostClaim) {
            log.warn("deck publish {}: claim lost, left to the new claim", id)
        } catch (e: PublishFailure) {
            log.warn("deck publish {} failed: {}", id, e.error)
            end(id, token, "failed", error = e.error)
        } catch (e: OpRefused) {
            log.warn("deck publish {}: op refused: {}", id, e.message)
            end(id, token, "failed", error = "${PublishFailure.INTERNAL}: op refused: ${e.message}")
        } catch (e: Exception) {
            log.error("deck publish {} failed", id, e)
            end(id, token, "failed", error = "${PublishFailure.INTERNAL}: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            files.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun read(file: Path): Archive {
        val items = mutableListOf<DeckItemRecord>()
        val sink = object : DeckItemSink {
            override fun item(r: DeckItemRecord) {
                items.add(r)
            }
        }
        val summary = try {
            Files.newInputStream(file).buffered().use { SnapshotReader.readDeckItems(it, sink) }
        } catch (e: SnapshotInvalid) {
            throw PublishFailure(PublishFailure.INVALID_SNAPSHOT, e.message ?: "invalid archive")
        } catch (e: IOException) {
            throw PublishFailure(PublishFailure.INVALID_SNAPSHOT, "cannot decompress: ${e.message}")
        }
        return Archive(summary.header, items)
    }

    /** The `validate.py deck-publish` rules (5.2 step 3), `invalid_snapshot`. */
    private fun check(p: DeckPublishPayload, a: Archive) {
        fun bad(m: String): Nothing = throw PublishFailure(PublishFailure.INVALID_SNAPSHOT, m)
        val h = a.header
        if (h.deckType != "list") bad("deck_type must be list, is ${h.deckType}")
        if (h.deckId != p.deckId || h.contentVersion != p.version) {
            bad("archive header deck_id/content_version ${h.deckId}/${h.contentVersion} != payload ${p.deckId}/${p.version}")
        }
        if (a.items.isEmpty()) bad("empty list (a deck-build with no items fails instead of publishing)")
        a.items.forEachIndexed { i, r -> if (r.position != 1024L * (i + 1)) bad("positions are not 1024, 2048, ... (item ${i + 1} has ${r.position})") }
    }

    private fun parentStatus(parent: UUID): String? =
        db.txBlocking { it.fetchValue("select status from app.job where id = ?", parent) as String? }

    // ------------------------------------------------------------------ the transaction (5.2 steps 4-8)

    private fun publish(tx: OpTx, id: UUID, token: String, p: DeckPublishPayload, a: Archive, result: ObjectNode): String {
        val dsl = tx.dsl
        // 4. The claim is still ours and the parent still running.
        val child = dsl.fetchOptional("select status, locked_by from app.job where id = ? for update", id).orElse(null)
        if (child == null || child.get(0, String::class.java) != "running" || child.get(1, String::class.java) != token) throw LostClaim()
        val parent = dsl.fetchOptional("select status from app.job where id = ? for update", p.parentJobId).orElse(null)?.get(0, String::class.java)
        if (parent != "running") {
            endInTx(tx, id, token, "cancelled")
            return "cancelled"
        }

        // 5. The deck and the archive's references.
        val deck = dsl.fetchOptional(
            "select type, list_key, deleted_at is not null, content_version, content_blob_hash, created_at from app.deck where id = ?::uuid for update",
            p.deckId,
        ).orElse(null) ?: fail(UNKNOWN_DECK, "no deck ${p.deckId}")
        val type = deck.get(0, String::class.java)
        val listKey = deck.get(1, String::class.java)
        if (type != "list") fail(PublishFailure.INVALID_PAYLOAD, "deck ${p.deckId} is a $type deck, not a list deck")
        if (listKey != p.listKey) fail(PublishFailure.INVALID_PAYLOAD, "deck ${p.deckId} has list_key $listKey, payload says ${p.listKey}")
        if (deck.get(2, Boolean::class.java)) fail(DECK_DELETED, "deck ${p.deckId} is deleted")
        val current = deck.get(3, Int::class.java)
        if (current == p.version && deck.get(4, String::class.java) == p.deckItems.blobHash) {
            // Already published from this very archive (a re-claim after the effects committed): change nothing.
            succeed(tx, id, token, p, result)
            return "already published"
        }
        if (current != p.baseContentVersion) fail(PublishFailure.STALE_BASE, "deck ${p.deckId} is at version $current, payload built on ${p.baseContentVersion}")
        val seqs = a.items.map { it.entrySeq }
        val known = seqs.chunked(10_000).sumOf { chunk ->
            (dsl.fetchValue("select count(*) from ref.dict_entry where seq = any(?::bigint[])", chunk.toTypedArray()) as Number).toLong()
        }
        if (known != seqs.size.toLong()) fail(PublishFailure.INVALID_SNAPSHOT, "${seqs.size - known} item(s) name an entry_seq not in ref.dict_entry")

        // 6. Install the archive (SNAPSHOT.md 7 step 4, rows per 4.3).
        install(tx, p.deckId, a.items, deck.get(5, OffsetDateTime::class.java))

        // 7. Announce the new version.
        tx.emit(
            OpType.DECK_CONTENT_PUBLISHED,
            JsonObject(
                linkedMapOf(
                    "deck_id" to JsonString(p.deckId), "content_version" to JsonNumber(p.version.toDouble()),
                    "content_blob_hash" to JsonString(p.deckItems.blobHash),
                ),
            ),
        )

        // 8. The child succeeds with its effects; the parent shows "publishing".
        succeed(tx, id, token, p, result)
        return "published v${p.version}"
    }

    private fun install(tx: OpTx, deckId: String, items: List<DeckItemRecord>, addedAt: OffsetDateTime) {
        val deck = UUID.fromString(deckId)
        tx.dsl.execute("delete from app.deck_item where deck_id = ?::uuid", deckId)
        tx.dsl.connection { c ->
            c.prepareStatement(
                "insert into app.deck_item (deck_id, item_key, entry_seq, note_id, position, occurrence_count, added_at, deleted_at, hlc) " +
                    "values (?, ?, ?, null, ?, ?, ?, null, null)",
            ).use { ps ->
                items.chunked(1000).forEach { chunk ->
                    for (r in chunk) {
                        ps.setObject(1, deck)
                        ps.setString(2, "e:${r.entrySeq}")
                        ps.setLong(3, r.entrySeq)
                        ps.setLong(4, r.position)
                        ps.setInt(5, r.occurrenceCount)
                        ps.setObject(6, addedAt)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
    }

    private fun succeed(tx: OpTx, id: UUID, token: String, p: DeckPublishPayload, result: ObjectNode) {
        val n = tx.dsl.execute(
            "update app.job set status = 'succeeded', result = ?::jsonb, progress = 1, finished_at = now(), locked_by = null, error = null " +
                "where id = ? and status = 'running' and locked_by = ?",
            json.writeValueAsString(result), id, token,
        )
        if (n != 1) throw LostClaim()
        tx.dsl.execute(
            "update app.job set progress = greatest(progress, 0.95), progress_message = 'publishing' where id = ? and status = 'running'",
            p.parentJobId,
        )
        tx.dsl.execute("select pg_notify('kotobaya_events', ?)", """{"type":"job.updated","data":{"id":"${p.parentJobId}"}}""")
    }

    private fun endInTx(tx: OpTx, id: UUID, token: String, status: String) {
        val n = tx.dsl.execute(
            "update app.job set status = ?, finished_at = now(), locked_by = null where id = ? and status = 'running' and locked_by = ?",
            status, id, token,
        )
        if (n != 1) throw LostClaim()
    }

    /** Ends the child outside the publish transaction (failure, early cancel); guarded by the claim. */
    private fun end(id: UUID, token: String, status: String, error: String? = null) {
        db.txBlocking { dsl ->
            dsl.execute(
                "update app.job set status = ?, error = ?, finished_at = now(), locked_by = null where id = ? and status = 'running' and locked_by = ?",
                status, error?.let(::cut), id, token,
            )
        }
    }

    private class Beat(private val thread: Thread) {
        fun stop() {
            thread.interrupt()
            thread.join(5_000)
        }
    }

    private fun startHeartbeat(id: UUID, token: String): Beat {
        val t = Thread {
            try {
                while (true) {
                    Thread.sleep(heartbeatMs)
                    runCatching {
                        db.txBlocking { it.execute("update app.job set heartbeat_at = now() where id = ? and status = 'running' and locked_by = ?", id, token) }
                    }.onFailure { log.warn("deck publish heartbeat failed: {}", it.message) }
                }
            } catch (_: InterruptedException) {
                // stopped
            }
        }
        t.isDaemon = true
        t.name = "deck-publish-heartbeat"
        t.start()
        return Beat(t)
    }

    companion object {
        const val KIND = "deck-publish"
        const val UNKNOWN_DECK = "unknown_deck"
        const val DECK_DELETED = "deck_deleted"

        private fun fail(code: String, message: String): Nothing = throw PublishFailure(code, message)

        /** At most 4,000 UTF-16 units, never splitting a surrogate pair. */
        private fun cut(s: String): String {
            if (s.length <= 4000) return s
            val end = if (Character.isHighSurrogate(s[3999])) 3999 else 4000
            return s.substring(0, end)
        }
    }
}
