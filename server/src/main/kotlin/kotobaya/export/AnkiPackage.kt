package kotobaya.export

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.blobs.BlobStore
import kotobaya.cards.CardSearch
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.cards.TemplateInfo
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.notes.toNoteTypeInfo
import kotobaya.render.DefaultCardRenderer
import kotobaya.server.Database
import kotobaya.server.json
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A finished `.apkg`: the SQLite collection sits in a temp file, media bytes are streamed from the blob store when
 * [writeTo] runs. [close] deletes the temp file.
 *
 * Mapping: one Anki model per Kotobaya note type (fields = field keys, then `kb_front_<kind>` / `kb_back_<kind>` per
 * card kind holding the core-rendered HTML of that note's card, so dictionary fill-in survives), one template per kind
 * (`{{kb_front_<kind>}}` / `{{kb_back_<kind>}}`).
 */
internal class AnkiPackage(
    private val collection: Path,
    private val store: BlobStore?,
    /** Zip entry number = list index; (blob hash, file name). */
    private val media: List<Pair<String, String>>,
) : AutoCloseable {
    suspend fun writeTo(out: OutputStream) = withContext(Dispatchers.IO) {
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry("collection.anki21"))
        Files.newInputStream(collection).use { it.copyTo(zip) }
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("media"))
        val map = linkedMapOf<String, String>()
        media.forEachIndexed { i, (_, name) -> map[i.toString()] = name }
        zip.write(json.writeValueAsBytes(map))
        zip.closeEntry()
        media.forEachIndexed { i, (hash, _) ->
            zip.putNextEntry(ZipEntry(i.toString()))
            store?.get(hash)?.use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    override fun close() {
        runCatching { Files.deleteIfExists(collection) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AnkiPackage::class.java)
        private const val HASH = "[0-9a-f]{64}"
        private val BLOB_REF = Regex("blob:($HASH)")
        private val SOUND_REF = Regex("\\[sound:($HASH)]")
        private val IMG_SRC = Regex("src=\"($HASH)\"")
        private val AUDIO_BUTTON = Regex("<button[^>]*data-kb-audio=\"($HASH)\"[^>]*></button>")
        private val TAGS = Regex("<[^>]*>")
        private val OPTIONS = RenderOptions(furigana = true, pitch = false, showMnemonic = true, mediaUrlTemplate = "{sha256}")

        private class CardRow(
            val id: String, val noteId: String, val kind: String, val suspended: Boolean, val buried: Boolean, val flags: Int,
            val state: String, val stability: Double?, val difficulty: Double?, val dueEpoch: Double?, val intervalDays: Int?,
            val reps: Int, val lapses: Int, val retention: Double, val lastReviewMs: Long?,
        )

        private class NoteRow(
            val id: String, val noteTypeId: String, val entrySeq: Long?, val kanji: String?,
            val fields: Map<String, String>, val tags: List<String>, val mnemonic: String,
        )

        private class ReviewRow(val cardId: String, val atMs: Long, val rating: Int, val durationMs: Int, val learning: Boolean)

        suspend fun build(db: Database, store: BlobStore?, query: String?, now: Instant): AnkiPackage = withContext(Dispatchers.IO) {
            val file = Files.createTempFile("kotobaya-anki-", ".anki21")
            try {
                val mediaFiles = db.txBlocking { dsl ->
                    dsl.execute("set transaction isolation level repeatable read read only")
                    // A query that does not parse is 422 invalid_query (thrown by SmartDecks.parse inside).
                    val compiled = CardSearch.compile(dsl, query?.takeIf { it.isNotBlank() }, now.toEpochMilli())
                    val sel = compiled.select()
                    val cards = dsl.fetch(
                        "select c.id::text, c.note_id::text, c.kind, c.suspended, coalesce(c.buried_until > ?::timestamptz, false), c.flags, c.state, " +
                            "c.stability, c.difficulty, extract(epoch from c.due_at)::float8, c.interval_days, c.reps, c.lapses, p.desired_retention, " +
                            "(extract(epoch from c.last_review_at) * 1000)::bigint " +
                            "from app.card c join app.fsrs_params p on p.id = c.preset_id where c.id in (${sel.sql}) order by c.created_at, c.id",
                        now.toString(), *sel.params.toTypedArray(),
                    ).map {
                        CardRow(
                            it.get(0, String::class.java), it.get(1, String::class.java), it.get(2, String::class.java),
                            it.get(3, Boolean::class.javaObjectType), it.get(4, Boolean::class.javaObjectType), it.get(5, Int::class.javaObjectType),
                            it.get(6, String::class.java), it.get(7, Double::class.javaObjectType), it.get(8, Double::class.javaObjectType),
                            it.get(9, Double::class.javaObjectType), it.get(10, Int::class.javaObjectType), it.get(11, Int::class.javaObjectType),
                            it.get(12, Int::class.javaObjectType), it.get(13, Double::class.javaObjectType), it.get(14, Long::class.javaObjectType),
                        )
                    }
                    val notes = cards.map { it.noteId }.distinct().chunked(1000).flatMap { ids ->
                        dsl.fetch("select to_jsonb(n)::text from app.note n where n.id = any(?::uuid[])", ids.toTypedArray())
                            .map { json.readTree(it.get(0, String::class.java)) as ObjectNode }
                    }.map { n ->
                        NoteRow(
                            n["id"].asText(), n["note_type_id"].asText(),
                            n["entry_seq"]?.takeUnless { it.isNull }?.asLong(), n["kanji_literal"]?.takeUnless { it.isNull }?.asText(),
                            n["fields"].fields().asSequence().associate { (k, v) -> k to v.asText() },
                            n["tags"].map { it.asText() }, n["mnemonic"].asText(),
                        )
                    }.associateBy { it.id }
                    val noteTypes = dsl.fetch("select to_jsonb(t)::text from app.note_type t")
                        .map { json.readTree(it.get(0, String::class.java)) as ObjectNode }.associateBy { it["id"].asText() }
                    val hashes = collectBlobs(notes.values)
                    val blobTypes = if (hashes.isEmpty()) emptyMap() else dsl.fetch(
                        "select hash, content_type from app.blob where hash = any(?::text[])", hashes.toTypedArray(),
                    ).associate { it.get(0, String::class.java) to it.get(1, String::class.java) }
                    val files = LinkedHashMap<String, String>() // hash -> media file name
                    if (store != null) {
                        for (hash in hashes) {
                            val type = blobTypes[hash]
                            if (type == null || store.size(hash) == null) {
                                log.warn("export: blob {} is referenced but not available; left out of the package", hash)
                                continue
                            }
                            files[hash] = "$hash.${extension(type)}"
                        }
                    }
                    Files.deleteIfExists(file)
                    AnkiWriter(file, now).use { w ->
                        w.write(cards, notes, noteTypes, loadDictionary(dsl, notes.values), loadKanji(dsl, notes.values), files, loadRevlog(dsl, cards.map { it.id }))
                    }
                    files
                }
                AnkiPackage(file, store, mediaFiles.map { (h, n) -> h to n })
            } catch (e: Throwable) {
                runCatching { Files.deleteIfExists(file) }
                throw e
            }
        }

        private fun collectBlobs(notes: Collection<NoteRow>): Set<String> {
            val out = LinkedHashSet<String>()
            for (n in notes) for (v in n.fields.values) {
                BLOB_REF.findAll(v).forEach { out += it.groupValues[1] }
                SOUND_REF.findAll(v).forEach { out += it.groupValues[1] }
            }
            return out
        }

        private fun extension(contentType: String): String = when (val t = contentType.substringBefore(';').trim().lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/mp4", "audio/aac", "audio/x-m4a" -> "m4a"
            "audio/wav", "audio/x-wav" -> "wav"
            "image/jpeg" -> "jpg"
            "image/svg+xml" -> "svg"
            else -> t.substringAfter('/', "bin").filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
        }

        private fun loadDictionary(dsl: DSLContext, notes: Collection<NoteRow>): Map<Long, DictionaryData> {
            val out = HashMap<Long, DictionaryData>()
            for (chunk in notes.mapNotNull { it.entrySeq }.distinct().chunked(1000)) {
                val arr = chunk.toTypedArray()
                val glosses = dsl.fetch(
                    "select seq, gloss from ref.dict_entry_gloss where seq = any(?::bigint[]) and lang = 'eng' order by seq, sense_ord, gloss_ord", arr,
                ).groupBy({ it.get(0, Long::class.javaObjectType) }, { it.get(1, String::class.java) })
                dsl.fetch("select seq, headword, headword_reading from ref.dict_entry where seq = any(?::bigint[])", arr).forEach {
                    val seq = it.get(0, Long::class.javaObjectType)
                    out[seq] = DictionaryData(it.get(1, String::class.java), it.get(2, String::class.java), emptyList(), glosses[seq].orEmpty(), emptyList())
                }
            }
            return out
        }

        private fun loadKanji(dsl: DSLContext, notes: Collection<NoteRow>): Map<String, KanjiData> {
            val literals = notes.mapNotNull { it.kanji }.distinct()
            if (literals.isEmpty()) return emptyMap()
            return dsl.fetch(
                "select literal, meanings, on_readings, kun_readings, stroke_count from ref.kanji where literal = any(?::text[])",
                literals.toTypedArray(),
            ).associate {
                val lit = it.get(0, String::class.java)
                lit to KanjiData(
                    lit, it.get(1, Array<String>::class.java).toList(), it.get(2, Array<String>::class.java).toList(),
                    it.get(3, Array<String>::class.java).toList(), it.get(4, Int::class.javaObjectType),
                )
            }
        }

        /** Scheduled and learning reviews only: `manual`, `drill`, `cram` and undone rows are not exported. */
        private fun loadRevlog(dsl: DSLContext, cardIds: List<String>): List<ReviewRow> = cardIds.chunked(1000).flatMap { ids ->
            dsl.fetch(
                "select card_id::text, (extract(epoch from reviewed_at) * 1000)::bigint, rating, coalesce(duration_ms, 0), review_type " +
                    "from app.review_log where card_id = any(?::uuid[]) and review_type in ('scheduled', 'learning') and undone_at is null and rating is not null " +
                    "order by reviewed_at, hlc, id",
                ids.toTypedArray(),
            ).map {
                ReviewRow(
                    it.get(0, String::class.java), it.get(1, Long::class.javaObjectType), it.get(2, Int::class.javaObjectType),
                    it.get(3, Int::class.javaObjectType), it.get(4, String::class.java) == "learning",
                )
            }
        }

        private class IdGen(start: Long) {
            private var n = start
            fun next(): Long = n++
        }

        private class AnkiWriter(file: Path, private val now: Instant) : AutoCloseable {
            private val conn = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")

            init {
                conn.autoCommit = false
                conn.createStatement().use { st -> for (ddl in SCHEMA) st.execute(ddl) }
            }

            override fun close() = conn.close()

            fun write(
                cards: List<CardRow>, notes: Map<String, NoteRow>, noteTypes: Map<String, ObjectNode>, dict: Map<Long, DictionaryData>,
                kanji: Map<String, KanjiData>, mediaFiles: Map<String, String>, reviews: List<ReviewRow>,
            ) {
                val nowSec = now.epochSecond
                val crt = nowSec - Math.floorMod(nowSec, 86400L)
                val infos = HashMap<String, NoteTypeInfo>()
                for (id in notes.values.map { it.noteTypeId }.distinct()) {
                    val nt = noteTypes[id] ?: continue
                    val info = nt.toNoteTypeInfo()
                    // A card whose kind is no longer in the note type's templates still gets a template of its own.
                    val extra = cards.filter { notes[it.noteId]?.noteTypeId == id }.map { it.kind }.distinct().filter { k -> info.templates.none { it.kind == k } }
                    infos[id] = info.copy(templates = info.templates + extra.map { TemplateInfo(it, false, null, null) })
                }
                val ids = IdGen(now.toEpochMilli())
                val modelIds = infos.keys.associateWith { ids.next() }
                val models = linkedMapOf<String, Any>()
                for ((typeId, mid) in modelIds) models[mid.toString()] = model(mid, noteTypes.getValue(typeId), infos.getValue(typeId), nowSec)
                conn.prepareStatement("insert into col values (1, ?, ?, ?, 11, 0, 0, 0, ?, ?, ?, ?, '{}')").use {
                    it.setLong(1, crt)
                    it.setLong(2, now.toEpochMilli())
                    it.setLong(3, now.toEpochMilli())
                    it.setString(4, json.writeValueAsString(conf(modelIds.values.firstOrNull() ?: 0L)))
                    it.setString(5, json.writeValueAsString(models))
                    it.setString(6, json.writeValueAsString(mapOf("1" to deck(nowSec))))
                    it.setString(7, json.writeValueAsString(mapOf("1" to dconf(nowSec))))
                    it.executeUpdate()
                }
                val cardsByNote = cards.groupBy { it.noteId }
                val noteAnkiId = HashMap<String, Long>()
                val cardAnkiId = HashMap<String, Long>()
                val ns = conn.prepareStatement("insert into notes values (?, ?, ?, ?, -1, ?, ?, ?, ?, 0, '')")
                val cs = conn.prepareStatement("insert into cards values (?, ?, 1, ?, ?, -1, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?)")
                var newPos = 0
                for (card in cards) {
                    val note = notes[card.noteId] ?: continue
                    val info = infos[note.noteTypeId] ?: continue
                    if (note.id !in noteAnkiId) {
                        val nid = ids.next()
                        noteAnkiId[note.id] = nid
                        val noteCards = cardsByNote.getValue(note.id)
                        val values = info.fieldKeys.map { rewrite(note.fields[it].orEmpty(), mediaFiles) }.toMutableList()
                        val dd = note.entrySeq?.let { dict[it] }
                        val kd = note.kanji?.let { kanji[it] }
                        for (t in info.templates) {
                            val has = noteCards.any { it.kind == t.kind }
                            for (side in listOf(CardSide.FRONT, CardSide.BACK)) {
                                values += if (!has) "" else rewrite(
                                    DefaultCardRenderer.render(RenderInput(info, t.kind, note.fields, note.mnemonic, dd, null, side, OPTIONS, kd)).html,
                                    mediaFiles,
                                )
                            }
                        }
                        val first = values.firstOrNull().orEmpty()
                        ns.setLong(1, nid)
                        ns.setString(2, note.id)
                        ns.setLong(3, modelIds.getValue(note.noteTypeId))
                        ns.setLong(4, nowSec)
                        ns.setString(5, if (note.tags.isEmpty()) "" else note.tags.joinToString(" ", " ", " ") { it.trim().replace(Regex("\\s+"), "_") })
                        ns.setString(6, values.joinToString("\u001f"))
                        ns.setString(7, first)
                        ns.setLong(8, csum(first))
                        ns.executeUpdate()
                    }
                    val cid = ids.next()
                    cardAnkiId[card.id] = cid
                    val due = card.dueEpoch?.toLong() ?: nowSec
                    val (type, queue, dueValue) = when (card.state) {
                        "new" -> Triple(0, 0, (++newPos).toLong())
                        "learning" -> Triple(1, 1, due)
                        "relearning" -> Triple(3, 1, due)
                        else -> Triple(2, 2, Math.floorDiv(due - crt, 86400L))
                    }
                    val data = linkedMapOf<String, Any>()
                    if (card.stability != null && card.difficulty != null) {
                        data["s"] = card.stability
                        data["d"] = card.difficulty
                        data["dr"] = card.retention
                        card.lastReviewMs?.let { data["lrt"] = it / 1000 }
                    }
                    cs.setLong(1, cid)
                    cs.setLong(2, noteAnkiId.getValue(note.id))
                    cs.setInt(3, info.templates.indexOfFirst { it.kind == card.kind })
                    cs.setLong(4, nowSec)
                    cs.setInt(5, type)
                    cs.setInt(6, if (card.suspended) -1 else if (card.buried) -3 else queue)
                    cs.setLong(7, dueValue)
                    cs.setInt(8, card.intervalDays ?: 0)
                    cs.setInt(9, if (card.state == "new") 0 else 2500)
                    cs.setInt(10, card.reps)
                    cs.setInt(11, card.lapses)
                    cs.setInt(12, card.flags)
                    cs.setString(13, json.writeValueAsString(data))
                    cs.executeUpdate()
                }
                ns.close()
                cs.close()
                conn.prepareStatement("insert into revlog values (?, ?, -1, ?, 0, 0, 0, ?, ?)").use { rs ->
                    val usedIds = HashSet<Long>()
                    for (r in reviews) {
                        val cid = cardAnkiId[r.cardId] ?: continue
                        var id = r.atMs
                        while (!usedIds.add(id)) id++
                        rs.setLong(1, id)
                        rs.setLong(2, cid)
                        rs.setInt(3, r.rating)
                        rs.setInt(4, r.durationMs)
                        rs.setInt(5, if (r.learning) 0 else 1)
                        rs.executeUpdate()
                    }
                }
                conn.commit()
            }

            private fun rewrite(html: String, mediaFiles: Map<String, String>): String {
                var s = AUDIO_BUTTON.replace(html) { m -> mediaFiles[m.groupValues[1]]?.let { "[sound:$it]" } ?: "" }
                s = SOUND_REF.replace(s) { m -> mediaFiles[m.groupValues[1]]?.let { "[sound:$it]" } ?: "" }
                s = IMG_SRC.replace(s) { m -> mediaFiles[m.groupValues[1]]?.let { "src=\"$it\"" } ?: m.value }
                return BLOB_REF.replace(s) { m -> mediaFiles[m.groupValues[1]] ?: m.value }
            }

            private fun csum(s: String): Long {
                val d = MessageDigest.getInstance("SHA-1").digest(s.replace(TAGS, "").toByteArray(Charsets.UTF_8))
                return d.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
            }

            private fun model(mid: Long, nt: ObjectNode, info: NoteTypeInfo, nowSec: Long): Map<String, Any?> {
                val fieldNames = info.fieldKeys + info.templates.flatMap { listOf("kb_front_${it.kind}", "kb_back_${it.kind}") }
                return linkedMapOf(
                    "id" to mid, "name" to "Kotobaya ${nt["name"].asText()}", "type" to 0, "mod" to nowSec, "usn" to -1, "sortf" to 0, "did" to 1,
                    "tmpls" to info.templates.mapIndexed { i, t ->
                        linkedMapOf<String, Any?>(
                            "name" to t.kind, "ord" to i, "qfmt" to "{{kb_front_${t.kind}}}", "afmt" to "{{kb_back_${t.kind}}}",
                            "bqfmt" to "", "bafmt" to "", "did" to null, "bfont" to "", "bsize" to 0,
                        )
                    },
                    "flds" to fieldNames.mapIndexed { i, n ->
                        linkedMapOf<String, Any?>("name" to n, "ord" to i, "sticky" to false, "rtl" to false, "font" to "Arial", "size" to 20, "media" to emptyList<String>())
                    },
                    "css" to nt["css"].asText(), "latexPre" to "", "latexPost" to "", "latexsvg" to false,
                    "req" to info.templates.indices.map { i -> listOf(i, "any", listOf(info.fieldKeys.size + i * 2)) },
                    "tags" to emptyList<String>(), "vers" to emptyList<String>(),
                )
            }

            private fun conf(curModel: Long) = linkedMapOf<String, Any>(
                "nextPos" to 1, "estTimes" to true, "activeDecks" to listOf(1), "sortType" to "noteFld", "timeLim" to 0,
                "sortBackwards" to false, "addToCur" to true, "curDeck" to 1, "newBury" to true, "newSpread" to 0, "dueCounts" to true,
                "curModel" to curModel.toString(), "collapseTime" to 1200,
            )

            private fun deck(nowSec: Long) = linkedMapOf<String, Any>(
                "id" to 1, "name" to "Kotobaya", "mod" to nowSec, "usn" to -1, "lrnToday" to listOf(0, 0), "revToday" to listOf(0, 0),
                "newToday" to listOf(0, 0), "timeToday" to listOf(0, 0), "collapsed" to false, "browserCollapsed" to false, "desc" to "",
                "dyn" to 0, "conf" to 1, "extendNew" to 0, "extendRev" to 0,
            )

            private fun dconf(nowSec: Long) = linkedMapOf<String, Any>(
                "id" to 1, "mod" to nowSec, "name" to "Default", "usn" to -1, "maxTaken" to 60, "autoplay" to true, "timer" to 0, "replayq" to true,
                "new" to linkedMapOf("bury" to true, "delays" to listOf(1.0, 10.0), "initialFactor" to 2500, "ints" to listOf(1, 4, 0), "order" to 1, "perDay" to 20),
                "rev" to linkedMapOf("bury" to true, "ease4" to 1.3, "ivlFct" to 1.0, "maxIvl" to 36500, "perDay" to 200, "hardFactor" to 1.2),
                "lapse" to linkedMapOf("delays" to listOf(10.0), "leechAction" to 1, "leechFails" to 8, "minInt" to 1, "mult" to 0.0),
            )

            companion object {
                val SCHEMA = listOf(
                    "create table col (id integer primary key, crt integer not null, mod integer not null, scm integer not null, ver integer not null, dty integer not null, usn integer not null, ls integer not null, conf text not null, models text not null, decks text not null, dconf text not null, tags text not null)",
                    "create table notes (id integer primary key, guid text not null, mid integer not null, mod integer not null, usn integer not null, tags text not null, flds text not null, sfld integer not null, csum integer not null, flags integer not null, data text not null)",
                    "create table cards (id integer primary key, nid integer not null, did integer not null, ord integer not null, mod integer not null, usn integer not null, type integer not null, queue integer not null, due integer not null, ivl integer not null, factor integer not null, reps integer not null, lapses integer not null, left integer not null, odue integer not null, odid integer not null, flags integer not null, data text not null)",
                    "create table revlog (id integer primary key, cid integer not null, usn integer not null, ease integer not null, ivl integer not null, lastIvl integer not null, factor integer not null, time integer not null, type integer not null)",
                    "create table graves (usn integer not null, oid integer not null, type integer not null)",
                    "create index ix_notes_usn on notes (usn)",
                    "create index ix_cards_usn on cards (usn)",
                    "create index ix_revlog_usn on revlog (usn)",
                    "create index ix_cards_nid on cards (nid)",
                    "create index ix_cards_sched on cards (did, queue, due)",
                    "create index ix_revlog_cid on revlog (cid)",
                    "create index ix_notes_csum on notes (csum)",
                )
            }
        }
    }
}
