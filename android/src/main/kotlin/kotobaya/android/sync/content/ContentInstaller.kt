package kotobaya.android.sync.content

import android.util.Log
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonString

/** Which archive (SNAPSHOT.md 1) a row announces. */
enum class ContentKind(val table: String) {
    /** `deck.content_blob_hash` of a media or list deck: `kotobaya-deck-items`. */
    DECK_ITEMS("deck"),

    /** `media.content_blob_hash`: `kotobaya-media-content`. */
    MEDIA_CONTENT("media"),
}

/** A live row whose announced `content_version` is newer than the installed one. */
data class PendingContent(val kind: ContentKind, val id: String, val version: Int, val hash: String)

sealed interface FetchResult {
    /** The blob's bytes in [file] (the installer verifies the hash again and deletes the file afterwards). */
    data class Ok(val file: File) : FetchResult

    /** Network, 5xx, 404: try again later (WorkManager backoff). */
    data class Transient(val reason: String) : FetchResult

    /** The server's copy can never match (corrupt, over the 512 MiB bound). */
    data class Permanent(val reason: String) : FetchResult
}

/** `GET /blobs/{hash}` to a local file. Blocking; runs on IO. */
fun interface SnapshotFetcher {
    fun fetch(hash: String): FetchResult
}

sealed interface InstallResult {
    data class Installed(val rows: Int) : InstallResult

    /** The row moved on, was tombstoned or is already installed: nothing to do for this announcement. */
    data object Stale : InstallResult

    /** The archive (hash, header or content) is unusable: nothing installed, not retried for this hash. */
    data class Rejected(val reason: String) : InstallResult

    /** Download failed transiently: nothing installed, retried later. */
    data class Retry(val reason: String) : InstallResult
}

data class InstallRunSummary(val installed: Int = 0, val rejected: Int = 0, val retry: Int = 0)

/**
 * The device installer of SNAPSHOT.md 7 (AND-DECKCONTENT): for every live media/list deck and live media whose
 * `content_version` > `installed_content_version`, fetch the blob named by `content_blob_hash`, verify its SHA-256,
 * validate the whole archive and its header against the row, then in ONE transaction replace the rows (deck_item, or
 * media_segment/sentence/occurrence/detected spans) and set `installed_content_version`. Until then the old rows stay.
 *
 * It writes no op and touches no learning data beyond those rows (deck and media content are server-built; SNAPSHOT.md
 * 7.4). A rejected archive is remembered per (row, hash) in device-local `sync_state` ([rejectedKey]) so it is not
 * downloaded again until a newer version is announced; the decks screen reports it as [kotobaya.android.decks.DeckContent.FAILED].
 * Errors are logged under [TAG].
 */
class ContentInstaller(
    private val store: SqlDelightDeviceStore,
    private val driver: SqlDriver,
    private val fetcher: SnapshotFetcher,
    private val log: (String) -> Unit = { Log.e(TAG, it) },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val info: (String) -> Unit = {},
) {
    private val changeCount = MutableStateFlow(0L)

    /** Bumped after every committed install (readers that show deck items reload on it). */
    val changes: StateFlow<Long> = changeCount.asStateFlow()

    /** Pending installs, deck items first (they drive the new-card queue), rejected hashes excluded. */
    fun pending(): List<PendingContent> {
        val decks = query(
            "SELECT d.id, d.content_version, d.content_blob_hash FROM deck d WHERE d.deleted_at IS NULL " +
                "AND d.type IN ('media', 'list') AND d.content_version > d.installed_content_version " +
                "AND d.content_blob_hash IS NOT NULL AND d.content_blob_hash IS NOT " +
                "(SELECT s.value FROM sync_state s WHERE s.key = '$REJECTED_PREFIX' || 'deck:' || d.id) ORDER BY d.priority, d.id",
        ) { pendingOf(ContentKind.DECK_ITEMS, it) }
        val media = query(
            "SELECT m.id, m.content_version, m.content_blob_hash FROM media m WHERE m.deleted_at IS NULL " +
                "AND m.content_version > m.installed_content_version AND m.content_blob_hash IS NOT NULL " +
                "AND m.content_blob_hash IS NOT " +
                "(SELECT s.value FROM sync_state s WHERE s.key = '$REJECTED_PREFIX' || 'media:' || m.id) ORDER BY m.id",
        ) { pendingOf(ContentKind.MEDIA_CONTENT, it) }
        return decks + media
    }

    /** Installs everything pending (re-reading the list until it is empty or only failures are left). */
    fun runAll(): InstallRunSummary {
        var s = InstallRunSummary()
        val tried = HashSet<PendingContent>()
        while (true) {
            val next = pending().firstOrNull { it !in tried } ?: return s
            tried += next
            s = when (install(next)) {
                is InstallResult.Installed -> s.copy(installed = s.installed + 1)
                is InstallResult.Rejected -> s.copy(rejected = s.rejected + 1)
                is InstallResult.Retry -> s.copy(retry = s.retry + 1)
                InstallResult.Stale -> s
            }
        }
    }

    fun install(p: PendingContent): InstallResult {
        val file = when (val f = fetcher.fetch(p.hash)) {
            is FetchResult.Ok -> f.file
            is FetchResult.Transient -> {
                log("${p.kind.table} ${p.id} v${p.version}: download of ${p.hash} failed, will retry: ${f.reason}")
                return InstallResult.Retry(f.reason)
            }
            is FetchResult.Permanent -> return reject(p, "download of ${p.hash}: ${f.reason}")
        }
        try {
            val actual = sha256(file)
            if (actual != p.hash) return reject(p, "hash mismatch: expected ${p.hash}, got $actual")
            // validate the whole archive and its header BEFORE the transaction (SNAPSHOT.md 7.1, 7.2)
            try {
                when (p.kind) {
                    ContentKind.DECK_ITEMS -> {
                        val h = file.inputStream().buffered().use { SnapshotReader.readDeckItems(it) }.header
                        val type = deckType(p.id)
                        if (h.deckId != p.id || h.contentVersion != p.version || h.deckType != type) {
                            return reject(p, "header mismatch: archive (${h.deckId}, ${h.deckType}, v${h.contentVersion}) vs row (${p.id}, $type, v${p.version})")
                        }
                    }
                    ContentKind.MEDIA_CONTENT -> {
                        val h = file.inputStream().buffered().use { SnapshotReader.readContent(it) }.header
                        if (h.mediaId != p.id || h.contentVersion != p.version) {
                            return reject(p, "header mismatch: archive (${h.mediaId}, v${h.contentVersion}) vs row (${p.id}, v${p.version})")
                        }
                    }
                }
            } catch (e: SnapshotInvalid) {
                return reject(p, "invalid archive: ${e.message}")
            } catch (e: IOException) {
                return reject(p, "unreadable archive: ${e.message}")
            }
            val rows: Int? = try {
                store.transaction { tx ->
                    if (!stillAnnounced(p)) return@transaction null
                    val n = when (p.kind) {
                        ContentKind.DECK_ITEMS -> installDeckItems(p, file)
                        ContentKind.MEDIA_CONTENT -> installMediaContent(p, file)
                    }
                    exec("UPDATE ${p.kind.table} SET installed_content_version = ? WHERE id = ?", 2) {
                        bindLong(0, p.version.toLong()); bindString(1, p.id)
                    }
                    tx.syncState.remove(rejectedKey(p.kind, p.id))
                    n
                }
            } catch (e: SnapshotInvalid) {
                return reject(p, "invalid archive: ${e.message}") // rolled back
            } catch (e: IOException) {
                return reject(p, "unreadable archive: ${e.message}") // rolled back
            }
            if (rows == null) return InstallResult.Stale
            changeCount.update { it + 1 }
            info("${p.kind.table} ${p.id}: installed v${p.version} ($rows rows)")
            return InstallResult.Installed(rows)
        } finally {
            file.delete()
        }
    }

    /** SNAPSHOT.md 7.2: the row is live and still announces exactly this version and hash, not yet installed. */
    private fun stillAnnounced(p: PendingContent): Boolean = query(
        "SELECT content_version, content_blob_hash, installed_content_version FROM ${p.kind.table} " +
            "WHERE id = ? AND deleted_at IS NULL",
        1, { bindString(0, p.id) },
    ) { c -> c.getLong(0) == p.version.toLong() && c.getString(1) == p.hash && (c.getLong(2) ?: 0L) < p.version }
        .singleOrNull() == true

    private fun deckType(id: String): String? =
        query("SELECT type FROM deck WHERE id = ?", 1, { bindString(0, id) }) { it.getString(0) }.singleOrNull()

    /** SNAPSHOT.md 7.4 / 4.3. */
    private fun installDeckItems(p: PendingContent, file: File): Int {
        val addedAt = query("SELECT created_at FROM deck WHERE id = ?", 1, { bindString(0, p.id) }) { it.getLong(0) ?: 0L }.single()
        exec("DELETE FROM deck_item WHERE deck_id = ?", 1) { bindString(0, p.id) }
        var n = 0
        file.inputStream().buffered().use {
            SnapshotReader.readDeckItems(
                it,
                object : DeckItemSink {
                    override fun item(r: DeckItemRecord) {
                        exec(
                            "INSERT INTO deck_item (deck_id, item_key, entry_seq, note_id, position, occurrence_count, added_at, deleted_at, hlc) " +
                                "VALUES (?, ?, ?, NULL, ?, ?, ?, NULL, NULL)",
                            6,
                        ) {
                            bindString(0, p.id)
                            bindString(1, "e:${r.entrySeq}")
                            bindLong(2, r.entrySeq)
                            bindLong(3, r.position)
                            bindLong(4, r.occurrenceCount.toLong())
                            bindLong(5, addedAt)
                        }
                        n++
                    }
                },
            )
        }
        return n
    }

    /** SNAPSHOT.md 7.3 (manual spans are never touched). */
    private fun installMediaContent(p: PendingContent, file: File): Int {
        val m = p.id
        exec("DELETE FROM occurrence WHERE media_id = ?", 1) { bindString(0, m) }
        exec("DELETE FROM sentence WHERE media_id = ?", 1) { bindString(0, m) }
        exec("DELETE FROM media_segment WHERE media_id = ?", 1) { bindString(0, m) }
        exec("DELETE FROM sentence_grammar_span WHERE media_id = ? AND origin = 'detected'", 1) { bindString(0, m) }
        val now = nowMs()
        var n = 0
        file.inputStream().buffered().use {
            SnapshotReader.readContent(
                it,
                object : ContentSink {
                    override fun segment(r: SegmentRecord) {
                        exec(
                            "INSERT INTO media_segment (id, media_id, ordinal, kind, title, sentence_count, token_count, unmapped_token_count) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            8,
                        ) {
                            bindString(0, r.id); bindString(1, m); bindLong(2, r.ordinal.toLong()); bindString(3, r.kind)
                            bindString(4, r.title); bindLong(5, r.sentenceCount.toLong()); bindLong(6, r.tokenCount.toLong())
                            bindLong(7, r.unmappedTokenCount.toLong())
                        }
                        n++
                    }

                    override fun sentence(r: SentenceRecord) {
                        exec(
                            "INSERT INTO sentence (id, media_id, segment_id, ordinal, text, tokens, origin, start_ms, end_ms, " +
                                "audio_blob_hash, image_blob_hash, location) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            12,
                        ) {
                            bindString(0, r.id); bindString(1, m); bindString(2, r.segmentId); bindLong(3, r.ordinal.toLong())
                            bindString(4, r.text); bindString(5, DefaultCanonicalJson.encode(r.tokens)); bindString(6, r.origin)
                            bindLong(7, r.startMs?.toLong()); bindLong(8, r.endMs?.toLong())
                            bindString(9, r.audioBlobHash); bindString(10, r.imageBlobHash)
                            bindString(11, r.location?.let(DefaultCanonicalJson::encode))
                        }
                        n++
                    }

                    override fun occurrence(r: OccurrenceRecord) {
                        exec(
                            "INSERT INTO occurrence (segment_id, entry_seq, media_id, count, pos_class, sentence_ids) VALUES (?, ?, ?, ?, ?, ?)",
                            6,
                        ) {
                            bindString(0, r.segmentId); bindLong(1, r.entrySeq); bindString(2, m); bindLong(3, r.count.toLong())
                            bindString(4, r.posClass)
                            bindString(5, DefaultCanonicalJson.encode(JsonArray(r.sentenceIds.map(::JsonString))))
                        }
                        n++
                    }

                    override fun grammarSpan(r: GrammarSpanRecord) {
                        exec(
                            "INSERT INTO sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, media_id, " +
                                "rule_index, created_at, deleted_at, hlc) VALUES (?, ?, ?, ?, 'detected', ?, ?, ?, NULL, NULL)",
                            7,
                        ) {
                            bindString(0, r.sentenceId); bindString(1, r.grammarPointId); bindLong(2, r.startOffset.toLong())
                            bindLong(3, r.endOffset.toLong()); bindString(4, m); bindLong(5, r.ruleIndex.toLong()); bindLong(6, now)
                        }
                        n++
                    }
                },
            )
        }
        return n
    }

    /** Forgets a rejected archive of [id], so the next run downloads it again (the user's "Retry"). */
    fun clearRejected(kind: ContentKind, id: String) {
        store.transaction { it.syncState.remove(rejectedKey(kind, id)) }
    }

    private fun pendingOf(kind: ContentKind, c: app.cash.sqldelight.db.SqlCursor) =
        PendingContent(kind, c.getString(0).orEmpty(), (c.getLong(1) ?: 0L).toInt(), c.getString(2).orEmpty())

    private fun reject(p: PendingContent, reason: String): InstallResult.Rejected {
        log("${p.kind.table} ${p.id} v${p.version}: nothing installed, $reason")
        store.transaction { it.syncState.put(rejectedKey(p.kind, p.id), p.hash) }
        return InstallResult.Rejected(reason)
    }

    private fun exec(sql: String, params: Int, bind: SqlPreparedStatement.() -> Unit) {
        driver.execute(null, sql, params, bind)
    }

    private fun <T> query(sql: String, map: (app.cash.sqldelight.db.SqlCursor) -> T): List<T> = query(sql, 0, {}, map)

    private fun <T> query(
        sql: String,
        params: Int,
        bind: SqlPreparedStatement.() -> Unit,
        map: (app.cash.sqldelight.db.SqlCursor) -> T,
    ): List<T> = driver.executeQuery(
        null,
        sql,
        { c ->
            val out = ArrayList<T>()
            while (c.next().value) out += map(c)
            QueryResult.Value(out)
        },
        params,
        bind,
    ).value

    companion object {
        const val TAG: String = "KotobayaContent"

        /** Device-local `sync_state` key prefix (`client.*`, never synced): value = the rejected blob hash. */
        const val REJECTED_PREFIX: String = "client.content_rejected."

        fun rejectedKey(kind: ContentKind, id: String): String = "$REJECTED_PREFIX${kind.table}:$id"

        private fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
