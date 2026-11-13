package kotobaya.media.publish

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotobaya.blobs.BlobStore
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.server.Database
import kotobaya.server.json
import kotobaya.sync.OpApply
import kotobaya.sync.OpRefused
import kotobaya.sync.OpTx
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * The server half of the `media-publish` hand-off (contracts/db/MEDIA_PIPELINE.md 3.3, C-11), the only claimer of
 * that server-internal job kind (SCHEMA.md 4.4).
 *
 * Per claimed child: validate the payload; end it `cancelled` if the parent is not running; fetch both archives
 * from the object store, check length and SHA-256, validate both archives and the pair fully ([SnapshotReader])
 * with no lock held; then, in ONE op transaction (advisory lock 7033001, taken by [OpApply]): re-check the claim
 * and the parent, lock and check the media, mint `media.created` + the media deck's `deck.created` (first publish
 * only), install the content and deck-item archives (SNAPSHOT.md 7 steps 3-4), mint `media.content_published`
 * (`parsed`, version base + 1) and `deck.content_published`, write the jpdb mapping queue (5.2), and end the child
 * `succeeded` with `JobResultMedia` as its `result`. The worker copies that result to the parent (3.2 step 4).
 * Any error rolls everything back; the child then ends `failed` with `<code>: <message>` in a new transaction.
 *
 * Idempotent: the child's `succeeded` commits with its effects, and a child whose version is already the media's
 * published version with the same archives (a re-claim after the effects committed) changes nothing and reports
 * the result computed from the archives again.
 *
 * Also runs the [MediaParseReconciler] (MEDIA_PIPELINE.md 4: `parsing` -> `failed` | `parsed`) at start and every
 * [reconcileMs].
 *
 * [faults] is a test seam: called with a stage name (`fetched`, `locked`, `installed`) so tests can crash or cancel
 * at a precise point. Production passes nothing.
 */
class MediaPublishRunner(
    private val db: Database,
    private val opApply: OpApply,
    private val objectStore: BlobStore,
    private val pollMs: Long = 1_000,
    private val staleAfterMs: Long = 300_000,
    private val heartbeatMs: Long = 30_000,
    private val reconcileMs: Long = 60_000,
    private val instanceId: String = "server-" + UUID.randomUUID().toString().take(8),
    private val faults: (String) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger("kotobaya.media.publish")
    val reconciler = MediaParseReconciler(db, opApply)

    /** The publish loop and the reconciler loop (W-5 calls this). */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        launch {
            while (isActive) {
                try {
                    reconciler.reconcileAll()
                } catch (e: Exception) {
                    log.warn("media parse reconciler: {}", e.message)
                }
                delay(reconcileMs)
            }
        }
        while (isActive) {
            val ran = try {
                runOnce()
            } catch (e: Exception) {
                log.warn("media publish runner: {}", e.message)
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

    private sealed interface Outcome {
        data object Done : Outcome
        data object Cancelled : Outcome
    }

    /** The claim moved to another runner (stale re-queue); roll back and leave the child alone. */
    private class LostClaim : RuntimeException("claim lost")

    private fun run(id: UUID, token: String, raw: JsonNode) {
        val files = mutableListOf<Path>()
        try {
            val payload = MediaPublishPayload.parse(raw)
            if (parentStatus(payload.parentJobId) != "running") {
                end(id, token, "cancelled")
                return
            }
            val content = fetch(payload.content, files)
            val deck = fetch(payload.deckItems, files)
            val contentSummary = snapshot { Files.newInputStream(content).buffered().use { SnapshotReader.readContent(it) } }
            val deckSummary = snapshot { Files.newInputStream(deck).buffered().use { SnapshotReader.readDeckItems(it) } }
            snapshot { SnapshotReader.checkPair(contentSummary, deckSummary) }
            checkPublish(payload, contentSummary)
            val result = result(payload, contentSummary, deckSummary)
            faults("fetched")
            val outcome = opApply.transactionBlocking(null) { tx ->
                publish(tx, id, token, payload, contentSummary, content, deck, result)
            }
            log.info("media publish {} for media {}: {}", id, payload.mediaId, outcome)
        } catch (e: LostClaim) {
            log.warn("media publish {}: claim lost, left to the new claim", id)
        } catch (e: PublishFailure) {
            log.warn("media publish {} failed: {}", id, e.error)
            end(id, token, "failed", error = e.error)
        } catch (e: OpRefused) {
            // The checks of step 5 passed but an op was refused: the database contradicts them.
            log.warn("media publish {}: op refused: {}", id, e.message)
            end(id, token, "failed", error = "${PublishFailure.INVARIANT_VIOLATION}: ${e.message}")
        } catch (e: Exception) {
            log.error("media publish {} failed", id, e)
            end(id, token, "failed", error = "${PublishFailure.INTERNAL}: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            files.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun <T> snapshot(block: () -> T): T = try {
        block()
    } catch (e: SnapshotInvalid) {
        throw PublishFailure(PublishFailure.INVALID_SNAPSHOT, e.message ?: "invalid archive")
    } catch (e: IOException) {
        throw PublishFailure(PublishFailure.INVALID_SNAPSHOT, "cannot decompress: ${e.message}")
    }

    private fun parentStatus(parent: UUID): String? =
        db.txBlocking { it.fetchValue("select status from app.job where id = ?", parent) as String? }

    /** Downloads [ref] to a temp file, checking the `app.blob` row, length and SHA-256 (3.3 step 3). */
    private fun fetch(ref: BlobRef, files: MutableList<Path>): Path {
        val hash = ref.blobHash
        val row = db.txBlocking { it.fetchExists(it.selectOne().from("app.blob").where("hash = ?", hash)) }
        if (!row) throw PublishFailure(PublishFailure.BLOB_MISSING, "no app.blob row for $hash")
        objectStore.size(hash) ?: throw PublishFailure(PublishFailure.BLOB_MISSING, "no object $hash in the object store")
        val file = Files.createTempFile("media-publish-", ".zst")
        files.add(file)
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        DigestInputStream(objectStore.get(hash), digest).use { input ->
            Files.newOutputStream(file).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    length += n
                    if (length > ref.sizeBytes) {
                        throw PublishFailure(PublishFailure.INVALID_PAYLOAD, "blob $hash is longer than size_bytes ${ref.sizeBytes}")
                    }
                    out.write(buf, 0, n)
                }
            }
        }
        if (length != ref.sizeBytes) throw PublishFailure(PublishFailure.INVALID_PAYLOAD, "blob $hash is $length bytes, size_bytes says ${ref.sizeBytes}")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != hash) throw PublishFailure(PublishFailure.INVALID_SNAPSHOT, "object $hash has SHA-256 $actual")
        return file
    }

    /** Header vs payload and the `validate.py publish` rules (3.3 step 3), `invalid_payload`. */
    private fun checkPublish(p: MediaPublishPayload, c: ContentSummary) {
        fun bad(m: String): Nothing = throw PublishFailure(PublishFailure.INVALID_PAYLOAD, m)
        if (c.header.mediaId != p.mediaId || c.header.contentVersion != p.version) {
            bad("archive header media_id/content_version ${c.header.mediaId}/${c.header.contentVersion} != payload ${p.mediaId}/${p.version}")
        }
        val jpdb = p.jpdb
        p.create?.let { cr ->
            if ((cr.source == "jpdb") != (jpdb != null)) bad("jpdb must be set iff create.source = jpdb")
            if ((cr.jpdbId != null) != (jpdb != null)) bad("create.jpdb_id must be set iff source = jpdb")
        }
        if (jpdb == null) {
            if (!p.unmapped.isEmpty) bad("unmapped must be [] for non-jpdb media")
            return
        }
        if (c.segments.size != 1 || c.segments[0].kind != "whole" || c.sentenceCount != 0) bad("jpdb: content must be one whole segment with no sentences")
        val vocab = jpdb.vocab
        if (vocab.map { it.jpdbVid }.toSet().size != vocab.size) bad("jpdb: duplicate jpdb_vid in vocab")
        if (vocab != vocab.sortedWith(compareBy({ -it.occurrenceCount.toLong() }, { it.jpdbVid }))) {
            bad("jpdb: vocab not ordered by occurrence_count desc, jpdb_vid asc")
        }
        for (v in vocab) {
            if ((v.resolution == "mapped") != (v.entrySeq != null)) bad("jpdb: vid ${v.jpdbVid}: entry_seq must be set iff resolution = mapped")
            if (v.entrySeq != null && v.entrySeq !in c.entryTotals) bad("jpdb: vid ${v.jpdbVid} mapped to ${v.entrySeq} but the content has no occurrence of it")
        }
        // Compared by value (Jackson's IntNode(1) != LongNode(1)); the payload parse already checked the shape.
        val expect = vocab.filter { it.resolution == "unresolved" }.map { listOf(it.jpdbVid, it.spelling, it.reading, it.occurrenceCount.toLong()) }
        val actual = p.unmapped.map { u ->
            listOf(u["jpdb_vid"].asLong(), u["spelling"].asText(), u["reading"].asText(), u["occurrence_count"].asLong())
        }
        if (actual != expect) bad("jpdb: unmapped must equal the unresolved vocab entries, in order")
        val notMapped = vocab.filter { it.resolution != "mapped" }.sumOf { it.occurrenceCount.toLong() }
        if (notMapped != c.unmappedTokens) bad("jpdb: unmapped_token_count ${c.unmappedTokens} != occurrences of unresolved + ignored vocab $notMapped")
    }

    /** `JobResultMedia` (3.3 step 10), computed from the archives. */
    private fun result(p: MediaPublishPayload, c: ContentSummary, d: DeckSummary): ObjectNode = json.createObjectNode().apply {
        put("media_id", p.mediaId)
        put("content_version", p.version)
        put("segments", c.segments.size)
        put("sentences", c.sentenceCount)
        put("tokens", c.tokens)
        put("unmapped_tokens", c.unmappedTokens)
        put("unique_entries", d.items.size)
        set<JsonNode>("unmapped", p.unmapped.deepCopy())
    }

    // ------------------------------------------------------------------ the transaction (3.3 steps 4-10)

    private fun publish(
        tx: OpTx,
        id: UUID,
        token: String,
        p: MediaPublishPayload,
        c: ContentSummary,
        contentFile: Path,
        deckFile: Path,
        result: ObjectNode,
    ): Outcome {
        val dsl = tx.dsl
        // 4. The claim is still ours and the parent still running.
        val child = dsl.fetchOptional("select status, locked_by from app.job where id = ? for update", id).orElse(null)
        if (child == null || child.get(0, String::class.java) != "running" || child.get(1, String::class.java) != token) throw LostClaim()
        val parent = dsl.fetchValue("select status from app.job where id = ? for update", p.parentJobId) as String?
        if (parent != "running") {
            endInTx(tx, id, token, "cancelled")
            return Outcome.Cancelled
        }
        faults("locked")

        // 5. The media, its deck, and the archive's references.
        val media = dsl.fetchOptional(
            "select content_version, content_blob_hash, deleted_at is not null, source, jpdb_id from app.media where id = ?::uuid for update",
            p.mediaId,
        ).orElse(null)
        val deck = dsl.fetchOptional(
            "select type, deleted_at is not null, content_version, content_blob_hash from app.deck where id = ?::uuid for update", p.mediaId,
        ).orElse(null)
        if (media != null && deck != null && !media.get(2, Boolean::class.java) &&
            media.get(0, Int::class.java) == p.version && media.get(1, String::class.java) == p.content.blobHash &&
            deck.get(2, Int::class.java) == p.version && deck.get(3, String::class.java) == p.deckItems.blobHash
        ) {
            // Already published from these very archives (a re-claim after the effects committed): change nothing.
            succeed(tx, id, token, p, result)
            return Outcome.Done
        }
        val create = p.create
        if (create != null) {
            if (media != null) fail(PublishFailure.STALE_BASE, "media ${p.mediaId} already exists (base_content_version 0)")
            if (deck != null) fail(PublishFailure.DECK_ID_TAKEN, "a ${deck.get(0, String::class.java)} deck already has id ${p.mediaId}")
            if (create.jpdbId != null &&
                dsl.fetchExists(dsl.selectOne().from("app.media").where("jpdb_id = ? and deleted_at is null", create.jpdbId))
            ) {
                fail(PublishFailure.JPDB_ID_TAKEN, "a live media already has jpdb_id ${create.jpdbId}")
            }
        } else {
            if (media == null) fail(PublishFailure.UNKNOWN_MEDIA, "no media ${p.mediaId}")
            if (media.get(2, Boolean::class.java)) fail(PublishFailure.MEDIA_DELETED, "media ${p.mediaId} is deleted")
            val current = media.get(0, Int::class.java)
            if (current != p.baseContentVersion) fail(PublishFailure.STALE_BASE, "media ${p.mediaId} is at version $current, payload built on ${p.baseContentVersion}")
            if (deck == null || deck.get(0, String::class.java) != "media" || deck.get(1, Boolean::class.java) || deck.get(2, Int::class.java) != current) {
                fail(PublishFailure.INVARIANT_VIOLATION, "media ${p.mediaId} has no live media deck at version $current")
            }
            if (p.jpdb != null && media.get(3, String::class.java) != "jpdb") fail(PublishFailure.INVALID_PAYLOAD, "a jpdb publish for a non-jpdb media")
        }
        c.sentenceIds.chunked(1000).forEach { chunk ->
            val foreign = dsl.fetchValue(
                "select id::text from app.sentence where id = any(?::uuid[]) and media_id <> ?::uuid limit 1", chunk.toTypedArray(), p.mediaId,
            ) as String?
            if (foreign != null) fail(PublishFailure.INVALID_SNAPSHOT, "sentence $foreign belongs to another media")
        }
        if (c.grammarPointIds.isNotEmpty()) {
            val known = (dsl.fetchValue("select count(*) from app.grammar_point where id = any(?::uuid[])", c.grammarPointIds.toTypedArray()) as Number).toInt()
            if (known != c.grammarPointIds.size) fail(PublishFailure.INVALID_SNAPSHOT, "a detected span names an unknown grammar_point_id")
        }

        // 6. First publish: media.created, then the media deck's deck.created caused by it.
        if (create != null) emitCreation(tx, p.mediaId, create)

        // 7. Install both archives.
        SnapshotInstall(dsl, p.mediaId).install(contentFile, deckFile)
        faults("installed")

        // 8. Announce the new version.
        tx.emit(
            OpType.MEDIA_CONTENT_PUBLISHED,
            obj(
                "media_id" to JsonString(p.mediaId), "parse_status" to JsonString("parsed"), "parse_error" to JsonNull,
                "content_version" to JsonNumber(p.version.toDouble()), "content_blob_hash" to JsonString(p.content.blobHash),
            ),
        )
        tx.emit(
            OpType.DECK_CONTENT_PUBLISHED,
            obj(
                "deck_id" to JsonString(p.mediaId), "content_version" to JsonNumber(p.version.toDouble()),
                "content_blob_hash" to JsonString(p.deckItems.blobHash),
            ),
        )

        // 9. jpdb mapping queue (5.2). Holds 7033001 only (lock order 3.4): never 7033011 here.
        p.jpdb?.let { jpdb ->
            for (v in jpdb.vocab) {
                dsl.execute(
                    "insert into app.jpdb_vocab (jpdb_vid, spelling, reading, first_seen_at) values (?, ?, ?, now()) " +
                        "on conflict (jpdb_vid) do update set spelling = excluded.spelling, reading = excluded.reading",
                    v.jpdbVid, v.spelling, v.reading,
                )
            }
            dsl.execute("delete from app.jpdb_vocab_media where media_id = ?::uuid", p.mediaId)
            for (v in jpdb.vocab) {
                dsl.execute(
                    "insert into app.jpdb_vocab_media (jpdb_vid, media_id, occurrence_count) values (?, ?::uuid, ?)",
                    v.jpdbVid, p.mediaId, v.occurrenceCount,
                )
            }
        }

        // 10. The child succeeds with its effects; the parent shows "publishing".
        succeed(tx, id, token, p, result)
        return Outcome.Done
    }

    /**
     * `media.created` then `deck.created {…, caused_by_op_id = the media.created}` (3.3 step 6).
     *
     * The contract says applying `media.created` emits nothing and the runner mints the deck explicitly. The
     * current `OpApplier.mediaCreated` (T202b, not owned here) still returns a `deck.created` follow-up with
     * default options; when it does, that follow-up IS the deck's `deck.created` (same name, type, media_id and
     * `caused_by_op_id`) and the payload's options are applied with one `deck.updated` right after it. Once the
     * applier follows the contract, the explicit branch below runs and the `deck.updated` disappears.
     */
    private fun emitCreation(tx: OpTx, mediaId: String, create: CreateMedia) {
        val mediaOp = tx.emit(
            OpType.MEDIA_CREATED,
            obj(
                "id" to JsonString(mediaId), "title" to JsonString(create.title), "kind" to JsonString(create.kind),
                "source" to JsonString(create.source), "jpdb_id" to (create.jpdbId?.let(::JsonString) ?: JsonNull),
                "cover_blob_hash" to JsonNull,
            ),
        )
        val npd: JsonValue = create.deck.newCardsPerDay?.let { JsonNumber(it.toDouble()) } ?: JsonNull
        val settings = obj("order" to JsonString("deck_frequency"))
        val followUp = tx.loggedOps.any { it.op.opType == OpType.DECK_CREATED && it.op.causedByOpId == mediaOp.op.opId }
        if (!followUp) {
            tx.emit(
                OpType.DECK_CREATED,
                obj(
                    "id" to JsonString(mediaId), "name" to JsonString(deckName(create.title)), "type" to JsonString("media"),
                    "media_id" to JsonString(mediaId), "priority" to JsonNumber(create.deck.priority.toDouble()),
                    "enabled" to JsonBool(create.deck.enabled), "new_cards_per_day" to npd, "settings" to settings,
                ),
                causedByOpId = mediaOp.op.opId,
            )
        } else {
            tx.emit(
                OpType.DECK_UPDATED,
                obj(
                    "id" to JsonString(mediaId),
                    "set" to obj(
                        "priority" to JsonNumber(create.deck.priority.toDouble()), "enabled" to JsonBool(create.deck.enabled),
                        "new_cards_per_day" to npd, "settings" to settings,
                    ),
                ),
                causedByOpId = mediaOp.op.opId,
            )
        }
    }

    private fun succeed(tx: OpTx, id: UUID, token: String, p: MediaPublishPayload, result: ObjectNode) {
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
        notify(tx.dsl, p.parentJobId)
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

    private fun notify(dsl: org.jooq.DSLContext, jobId: UUID) {
        dsl.execute("select pg_notify('kotobaya_events', ?)", """{"type":"job.updated","data":{"id":"$jobId"}}""")
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
                    }.onFailure { log.warn("media publish heartbeat failed: {}", it.message) }
                }
            } catch (_: InterruptedException) {
                // stopped
            }
        }
        t.isDaemon = true
        t.name = "media-publish-heartbeat"
        t.start()
        return Beat(t)
    }

    companion object {
        const val KIND = "media-publish"
        const val DECK_NAME_MAX = 200

        /** The media title cut to its first 200 code points (3.3 step 6). */
        fun deckName(title: String): String =
            if (title.codePointCount(0, title.length) <= DECK_NAME_MAX) title else title.substring(0, title.offsetByCodePoints(0, DECK_NAME_MAX))

        private fun fail(code: String, message: String): Nothing = throw PublishFailure(code, message)

        /** At most 4,000 UTF-16 units, never splitting a surrogate pair. */
        private fun cut(s: String): String {
            if (s.length <= 4000) return s
            val end = if (Character.isHighSurrogate(s[3999])) 3999 else 4000
            return s.substring(0, end)
        }

        internal fun obj(vararg members: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*members))
    }
}
