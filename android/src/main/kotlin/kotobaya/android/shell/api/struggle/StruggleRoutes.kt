package kotobaya.android.shell.api.struggle

import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.review.CardRows
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.actions.cursor
import kotobaya.android.shell.api.actions.pageLimit
import kotobaya.android.shell.api.actions.pageOffset
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.api.identity.obj
import kotobaya.android.shell.api.review.AnswerViews
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

private fun num(v: Long): JsonNumber = JsonNumber(v.toDouble())

private fun isNull(v: JsonValue?): Boolean = v == null || v == JsonNull

private fun hotLimit(call: LocalCall): Int {
    val q = call.query("limit")
    val e = FieldErrors()
    val limit = q.int("limit", 1..200, 20, e)
    e.throwIfAny()
    return limit
}

/**
 * `listHotWords`: the hot vocab notes ([HotSources], the server's rule over the replica), hottest first, each with its
 * entry summary (installed dictionary: package, then sidecar cache; never the network), note summary and live cards.
 * Nothing is minted; nothing is stored.
 */
internal fun listHotWords(call: LocalCall): ShellResponse {
    val limit = hotLimit(call)
    val now = call.services.get<ShellIdentity>().nowMs()
    val dict = call.services.get<InstalledDictionary>()
    val body = call.services.get<SqlDelightDeviceStore>().read { tx ->
        val t = tx.learning
        val threshold = AnswerViews.leechThreshold(t)
        val hot = HotSources(t, now)
        val items = hot.scoredNotes().take(limit).map { h -> hotWord(t, hot, dict, threshold, h) }
        obj("items" to JsonArray(items))
    }
    return ShellResponse.json(body)
}

private fun hotWord(t: LearningTables, hot: HotSources, dict: InstalledDictionary, threshold: Int, h: ScoredHot): JsonObject {
    val note = checkNotNull(CardRows.get(t, LearningTable.NOTE, h.noteId)) { "hot note ${h.noteId} vanished" }
    val cards = hot.liveCards(h.noteId)
        .sortedWith(compareBy<Row>({ (it["created_at"] as? JsonNumber)?.value ?: Double.MAX_VALUE }, { CardRows.str(it["id"]).orEmpty() }))
        .map { AnswerViews.card(it, note, threshold) }
    val noteJson = cards.firstOrNull()?.members?.get("note") ?: JsonNull
    val seq = (note["entry_seq"] as? JsonNumber)?.value?.toLong()
    return obj(
        "note_id" to JsonString(h.noteId),
        "entry_seq" to (seq?.let(::num) ?: JsonNull),
        "score" to JsonNumber(h.score),
        "failures_14d" to num(h.failures.toLong()),
        "entry" to (seq?.let { entrySummary(t, dict, it, h.noteId) } ?: JsonNull),
        "note" to noteJson,
        "cards" to JsonArray(cards.map { c -> JsonObject(LinkedHashMap(c.members).apply { remove("note") }) }),
    )
}

/** `DictEntrySummary`; null when the installed dictionary (package, cache) has no such entry. */
private fun entrySummary(t: LearningTables, dict: InstalledDictionary, seq: Long, noteId: String): JsonValue {
    val d = try {
        dict.entry(seq)
    } catch (e: RuntimeException) {
        null
    } ?: return JsonNull
    val senses = DefaultCanonicalJson.decode(d.senses.toString()).value as? JsonArray
    val glosses = ((senses?.items?.firstOrNull() as? JsonObject)?.members?.get("gloss") as? JsonArray)?.items.orEmpty()
        .mapNotNull { ((it as? JsonObject)?.members?.get("text") as? JsonString) }
    val status = CardRows.str(t.get(LearningTable.WORD_STATUS, listOf(num(seq)))?.get("status")) ?: "new"
    return obj(
        "seq" to num(seq),
        "headword" to JsonString(d.headword),
        "headword_reading" to JsonString(d.headwordReading),
        "is_common" to JsonBool(d.isCommon),
        "glosses" to JsonArray(glosses),
        "pos" to JsonArray(d.pos.map(::JsonString)),
        "freq_rank" to (d.freqRank?.let { num(it.toLong()) } ?: JsonNull),
        "retired" to JsonBool(d.retired),
        "status" to JsonString(status),
        "note_id" to JsonString(noteId),
        "deck_ids" to JsonArray(entryDeckIds(t, seq).map(::JsonString)),
    )
}

/** Live decks (smart ones too) with a live item `e:<seq>`, by priority then id (`DictEntry.deck_ids`). */
private fun entryDeckIds(t: LearningTables, seq: Long): List<String> =
    t.where(LearningTable.DECK_ITEM, "entry_seq", num(seq)).asSequence().filter { isNull(it["deleted_at"]) }
        .mapNotNull { CardRows.str(it["deck_id"]) }.distinct()
        .mapNotNull { CardRows.get(t, LearningTable.DECK, it) }.filter { isNull(it["deleted_at"]) }
        .sortedWith(compareBy<Row>({ (it["priority"] as? JsonNumber)?.value ?: 0.0 }, { CardRows.str(it["id"]).orEmpty() }))
        .mapNotNull { CardRows.str(it["id"]) }.toList()

/**
 * `listHotKanji`: the hot-word scores summed per kanji of each hot note's entry (the installed dictionary's
 * entry-to-kanji links; a note without a known entry contributes nothing). `word_count` = hot notes containing it.
 * Highest score first, then literal.
 */
internal fun listHotKanji(call: LocalCall): ShellResponse {
    val limit = hotLimit(call)
    val now = call.services.get<ShellIdentity>().nowMs()
    val dict = call.services.get<InstalledDictionary>()
    val body = call.services.get<SqlDelightDeviceStore>().read { tx ->
        val t = tx.learning
        val score = LinkedHashMap<String, Double>()
        val count = HashMap<String, Int>()
        for (h in HotSources(t, now).scoredNotes()) {
            val seq = (CardRows.get(t, LearningTable.NOTE, h.noteId)?.get("entry_seq") as? JsonNumber)?.value?.toLong() ?: continue
            val kanji = try {
                dict.entry(seq)?.kanji
            } catch (e: RuntimeException) {
                null
            }.orEmpty().distinct()
            for (lit in kanji) {
                score.merge(lit, h.score, Double::plus)
                count.merge(lit, 1, Int::plus)
            }
        }
        val top = score.entries.filter { it.value > 0.0 }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).take(limit)
        val items = top.map { (lit, s) ->
            val meanings = try {
                dict.kanji(lit)?.meanings
            } catch (e: RuntimeException) {
                null
            }.orEmpty()
            val kanjiNote = t.where(LearningTable.NOTE, "kanji_literal", JsonString(lit))
                .filter { CardRows.str(it["type"]) == "kanji" && CardRows.live(it) }
                .minWithOrNull(compareBy<Row>({ (it["created_at"] as? JsonNumber)?.value ?: Double.MAX_VALUE }, { CardRows.str(it["id"]).orEmpty() }))
            obj(
                "literal" to JsonString(lit),
                "score" to JsonNumber(s),
                "word_count" to num(count.getValue(lit).toLong()),
                "meanings" to JsonArray(meanings.map(::JsonString)),
                "note_id" to (kanjiNote?.get("id") ?: JsonNull),
            )
        }
        obj("items" to JsonArray(items))
    }
    return ShellResponse.json(body)
}

/**
 * `listLeeches`: live cards of live notes with `lapses >= scheduler.leech_threshold` (suspended included), most
 * lapses first, then card id; every note type. The server's offset cursors.
 */
internal fun listLeeches(call: LocalCall): ShellResponse {
    val q = call.query("limit", "cursor")
    val e = FieldErrors()
    val limit = pageLimit(q.first("limit"), e)
    val hash = "leeches"
    val offset = pageOffset(q.first("cursor"), hash, e)
    e.throwIfAny()
    val body = call.services.get<SqlDelightDeviceStore>().read { tx ->
        val t = tx.learning
        val threshold = AnswerViews.leechThreshold(t)
        val notes = HashMap<String, Row?>()
        val leeches = t.all(LearningTable.CARD).asSequence()
            .filter { CardRows.live(it) && ((it["lapses"] as? JsonNumber)?.value ?: 0.0) >= threshold }
            .filter { c ->
                val noteId = CardRows.str(c["note_id"]) ?: return@filter false
                notes.getOrPut(noteId) { CardRows.get(t, LearningTable.NOTE, noteId) }?.let { isNull(it["deleted_at"]) } == true
            }
            .sortedWith(compareByDescending<Row> { (it["lapses"] as? JsonNumber)?.value ?: 0.0 }.thenBy { CardRows.str(it["id"]).orEmpty() })
            .drop(offset).take(limit + 1).toList()
        val items = leeches.take(limit).map { c -> AnswerViews.card(c, checkNotNull(notes[CardRows.str(c["note_id"])]), threshold) }
        obj(
            "items" to JsonArray(items),
            "next_cursor" to if (leeches.size > limit) JsonString(cursor(offset + limit, hash)) else JsonNull,
        )
    }
    return ShellResponse.json(body)
}
