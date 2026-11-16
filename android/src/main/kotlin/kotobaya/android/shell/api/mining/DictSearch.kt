package kotobaya.android.shell.api.mining

import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import kotobaya.android.dict.DictEntryDetail
import kotobaya.android.dict.DictJson
import kotobaya.android.dict.DictSearchCursor
import kotobaya.android.dict.GapKind
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.text
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.dict.Romaji
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables

private const val NO_RANK = Int.MAX_VALUE

/** Prefix of every cursor the device issues (L-7: a cursor is only passed back to the party that issued it). */
internal const val LOCAL_CURSOR = "l."

private val b64e = Base64.getUrlEncoder().withoutPadding()
private val b64d = Base64.getUrlDecoder()

private fun badCursor(): Nothing = invalid("/cursor", "Malformed cursor, or the other parameters changed since it was issued.")

/** A local cursor: `l.` + base64url of `|`-joined [parts]. */
private fun encodeCursor(parts: List<Any?>): String = LOCAL_CURSOR + b64e.encodeToString(parts.joinToString("|") { it?.toString() ?: "" }.toByteArray())

private fun decodeCursor(s: String, size: Int): List<String> {
    val parts = runCatching { String(b64d.decode(s.removePrefix(LOCAL_CURSOR))).split('|') }.getOrNull() ?: badCursor()
    if (parts.size != size) badCursor()
    return parts
}

/**
 * The answer rule of SHELL.md L-7 for the paged dictionary lists (`searchDictionary`, `listKanjiWords`):
 * - a local cursor (`l.`) → local (no package: `422` at `/cursor`); any other cursor → forwarded (offline: `422`);
 * - no cursor: **local** when a package is installed and it is authoritative and the local first page is not empty;
 *   otherwise **forward and merge** (the server's page with the overlay recomputed from the replica); the forward
 *   fails as in P-1 → the local answer when a package is installed, else `503 shell_dict_missing`.
 */
private suspend fun localOrForward(env: DictEnv, cursor: String?, kanjiWords: Boolean, local: () -> JsonObject): ShellResponse {
    if (cursor != null) {
        if (cursor.startsWith(LOCAL_CURSOR)) {
            if (!env.installed) badCursor()
            return ShellResponse.json(local())
        }
        return env.forward()?.let { env.overlaid(it, kanjiWords) } ?: badCursor()
    }
    var page: JsonObject? = null
    if (env.installed && env.authoritative) {
        page = local()
        if ((page.members["items"] as? JsonArray)?.items?.isNotEmpty() == true) return ShellResponse.json(page)
    }
    val forwarded = env.forward()
    if (forwarded != null) return env.overlaid(forwarded, kanjiWords)
    if (!env.installed) dictMissing("No dictionary is installed and the server cannot be reached; this search")
    return ShellResponse.json(page ?: local())
}

// --------------------------------------------------------------------------------------------- searchDictionary

/** Kana/kanji/romaji/English matching of `searchDictionary` (openapi.yaml), for entries held as details (the sidecar cache). */
internal object SearchMatch {
    fun foldKatakana(text: String): String = buildString(text.length) {
        for (ch in text) {
            val cp = ch.code
            append(if (cp in 0x30A1..0x30F6 || cp == 0x30FD || cp == 0x30FE) (cp - 0x60).toChar() else ch)
        }
    }

    private fun isKana(cp: Int) = cp in 0x3041..0x3096 || cp in 0x309D..0x309F || cp in 0x30A1..0x30FA ||
        cp in 0x30FC..0x30FF || cp in 0xFF66..0xFF9F

    private fun isKanji(cp: Int) = cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF ||
        cp in 0x20000..0x2FA1F || cp in 0x30000..0x323AF || cp == 0x3005 || cp == 0x3007

    fun isLatin(q: String): Boolean = q.codePoints().noneMatch { isKana(it) || isKanji(it) }

    /** The match grade of [e] for the NFKC-normalised, trimmed [q]: 0 exact, 1 prefix, 2 substring/gloss; null = no match. */
    fun grade(e: DictEntryDetail, q: String): Int? {
        val latin = isLatin(q)
        val kana = if (latin) Romaji.toHiragana(q) else q
        var best: Int? = null
        if (kana != null) {
            val key = foldKatakana(kana)
            val short = key.codePointCount(0, key.length) <= 2
            val forms = (0 until e.kanjiForms.length()).mapNotNull { e.kanjiForms.optJSONObject(it)?.optString("text") } +
                (0 until e.readings.length()).mapNotNull { e.readings.optJSONObject(it)?.optString("text") }
            for (f in forms) {
                val norm = foldKatakana(Normalizer.normalize(f, Normalizer.Form.NFKC))
                val g = when {
                    norm == key -> 0
                    norm.startsWith(key) -> 1
                    !short && norm.contains(key) -> 2
                    else -> null
                }
                if (g != null && (best == null || g < best)) best = g
            }
        }
        if (latin && best == null) {
            val needle = q.lowercase(Locale.ROOT)
            val hit = (0 until e.senses.length()).any { i ->
                val gl = e.senses.optJSONObject(i)?.optJSONArray("gloss")
                gl != null && (0 until gl.length()).any { j -> gl.optJSONObject(j)?.optString("text")?.lowercase(Locale.ROOT)?.contains(needle) == true }
            }
            if (hit) best = 2
        }
        return best
    }
}

private data class SearchKey(val g: Int, val f: Int, val seq: Long) : Comparable<SearchKey> {
    override fun compareTo(other: SearchKey): Int = compareValuesBy(this, other, { it.g }, { it.f }, { it.seq })
}

private fun queryHash(q: String, commonOnly: Boolean): String = Integer.toHexString("$q\u0000$commonOnly".hashCode())

/**
 * The local page of `searchDictionary`: the installed package's search (`InstalledDictionary.search`, the server's
 * grading and order) merged with the sidecar cache's entries matched the same way, by (grade, frequency, seq).
 */
private fun localSearch(env: DictEnv, q: String, commonOnly: Boolean, limit: Int, cursor: String?): JsonObject {
    val hash = queryHash(q, commonOnly)
    val after = cursor?.let {
        val p = decodeCursor(it, 5)
        if (p[3] != env.releaseId.toString() || p[4] != hash) badCursor()
        SearchKey(p[0].toIntOrNull() ?: badCursor(), p[1].toIntOrNull() ?: badCursor(), p[2].toLongOrNull() ?: badCursor())
    }
    val pkg = env.dict.search(q, commonOnly, limit, after?.let { DictSearchCursor(it.g, it.f, it.seq) })
    val pkgSeqs = pkg.items.mapTo(HashSet()) { it.seq }
    val cached = env.cache.rows(GapKind.ENTRY).mapNotNull { row ->
        val e = row.body?.let { runCatching { DictJson.entry(it) }.getOrNull() } ?: return@mapNotNull null
        if (e.retired || (commonOnly && !e.isCommon) || e.seq in pkgSeqs) return@mapNotNull null
        if (env.dict.presentEntries(listOf(e.seq)).isNotEmpty()) return@mapNotNull null // the package answers for it
        val g = SearchMatch.grade(e, q) ?: return@mapNotNull null
        SearchKey(g, e.freqRank ?: NO_RANK, e.seq).takeIf { k -> after == null || k > after }?.let { it to e }
    }
    if (cached.isEmpty()) {
        val next = pkg.next?.let { encodeCursor(listOf(it.grade, it.freq, it.seq, env.releaseId, hash)) }
        val views = env.read { t -> pkg.items.map { DictViews.summary(t, it) } }
        return jsonObj("items" to JsonArray(views), "next_cursor" to (next?.let(::JsonString) ?: JsonNull))
    }
    // rare: gap-filled entries match too; grade the package's page the same way to merge by (grade, frequency, seq)
    val fromPkg = pkg.items.map { s ->
        val g = env.lookup.entry(s.seq)?.let { SearchMatch.grade(it, q) } ?: 2
        SearchKey(g, s.freqRank ?: NO_RANK, s.seq) to { t: LearningTables -> DictViews.summary(t, s) }
    }
    val fromCache = cached.map { (k, e) -> k to { t: LearningTables -> DictViews.summary(t, e) } }
    val merged = (fromPkg + fromCache).sortedBy { it.first }
    val page = merged.take(limit)
    val more = merged.size > limit || pkg.next != null
    val next = if (more && page.isNotEmpty()) page.last().first.let { encodeCursor(listOf(it.g, it.f, it.seq, env.releaseId, hash)) } else null
    val views = env.read { t -> page.map { it.second(t) } }
    return jsonObj("items" to JsonArray(views), "next_cursor" to (next?.let(::JsonString) ?: JsonNull))
}

/** `searchDictionary` (L-7): validation as the server's `DictApi.search`, then [localOrForward]. */
internal suspend fun searchDictionary(call: LocalCall): ShellResponse {
    val params = call.query("q", "common_only", "limit", "cursor")
    val raw = params.first("q") ?: invalid("/q", "q is required.", "required")
    val q = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
    if (q.isEmpty()) invalid("/q", "q must not be empty.")
    if (q.codePointCount(0, q.length) > 100) invalid("/q", "q is at most 100 characters.")
    val commonOnly = when (val v = params.first("common_only")) {
        null -> false
        "true" -> true
        "false" -> false
        else -> invalid("/common_only", "common_only must be true or false, not \"$v\".")
    }
    val limit = params.first("limit")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..500 } ?: invalid("/limit", "limit must be an integer from 1 to 500.") } ?: 50
    val cursor = params.first("cursor")
    val env = DictEnv(call)
    return localOrForward(env, cursor, kanjiWords = false) { localSearch(env, q, commonOnly, limit, cursor) }
}

// --------------------------------------------------------------------------------------------- listKanjiWords

private data class KanjiKey(val o: Int, val f: Int, val seq: Long) : Comparable<KanjiKey> {
    override fun compareTo(other: KanjiKey): Int = compareValuesBy(this, other, { -it.o }, { it.f }, { it.seq })
}

/**
 * The local page of `listKanjiWords` (C-17): live entries of the package's `entry_kanji` plus cached entries containing
 * the literal; `deck_id` / `mine` filters and the deck order from the replica, as the server's `KanjiWordsApi`.
 */
private fun localKanjiWords(env: DictEnv, literal: String, deckId: String?, mine: Boolean, limit: Int, cursor: String?): JsonObject {
    val after = cursor?.let {
        val p = decodeCursor(it, 7)
        if (p[3] != literal || p[4] != (deckId ?: "") || p[5] != mine.toString() || p[6] != env.releaseId.toString()) badCursor()
        KanjiKey(p[0].toIntOrNull() ?: badCursor(), p[1].toIntOrNull() ?: badCursor(), p[2].toLongOrNull() ?: badCursor())
    }
    val keys = LinkedHashMap<Long, Int>()
    env.pkg.kanjiEntries(literal).forEach { keys[it.seq] = it.freqKey }
    env.cache.rows(GapKind.ENTRY).forEach { row ->
        val e = row.body?.let { runCatching { DictJson.entry(it) }.getOrNull() } ?: return@forEach
        if (!e.retired && literal in e.kanji && e.seq !in keys) keys[e.seq] = e.freqRank ?: NO_RANK
    }
    return env.read { t ->
        val occ: Map<Long, Int>? = deckId?.let { id -> deckOccurrences(t, id) }
        val mineSet: Set<Long>? = if (mine && deckId == null) mineEntries(t) else null
        val ordered = keys.entries.asSequence()
            .filter { occ == null || it.key in occ }
            .filter { mineSet == null || it.key in mineSet }
            .map { KanjiKey(occ?.get(it.key) ?: 0, it.value, it.key) }
            .filter { after == null || it > after }
            .sorted()
            .take(limit + 1)
            .toList()
        val page = ordered.take(limit)
        val next = if (ordered.size > limit) page.last().let { encodeCursor(listOf(it.o, it.f, it.seq, literal, deckId ?: "", mine, env.releaseId)) } else null
        val items = page.mapNotNull { k -> env.lookup.entry(k.seq)?.let { DictViews.summary(t, it, kanjiWord = true) } }
        jsonObj("items" to JsonArray(items), "next_cursor" to (next?.let(::JsonString) ?: JsonNull))
    }
}

/** `occurrence_count` per entry of the live items of deck [id]; `404` when the deck is not live. */
private fun deckOccurrences(t: LearningTables, id: String): Map<Long, Int> {
    val deck = t.get(LearningTable.DECK, listOf(JsonString(id)))
    if (deck == null || !deck["deleted_at"].isNullish()) throw ProblemException(Problems.notFound("No deck $id."))
    return t.where(LearningTable.DECK_ITEM, "deck_id", JsonString(id)).filter { it["deleted_at"].isNullish() }
        .mapNotNull { r -> (r["entry_seq"] as? JsonNumber)?.value?.toLong()?.let { it to ((r["occurrence_count"] as? JsonNumber)?.value?.toInt() ?: 0) } }
        .toMap()
}

/** `mine=true`: entries that are a live item of a live deck, or have a live vocab note. */
private fun mineEntries(t: LearningTables): Set<Long> {
    val out = HashSet<Long>()
    val liveDecks = t.all(LearningTable.DECK).filter { it["deleted_at"].isNullish() }.mapNotNullTo(HashSet()) { it["id"].text() }
    t.all(LearningTable.DECK_ITEM).forEach { r ->
        if (r["deleted_at"].isNullish() && r["deck_id"].text() in liveDecks) (r["entry_seq"] as? JsonNumber)?.value?.toLong()?.let(out::add)
    }
    t.all(LearningTable.NOTE).forEach { n ->
        if (n["deleted_at"].isNullish() && n["type"].text() == "vocab") (n["entry_seq"] as? JsonNumber)?.value?.toLong()?.let(out::add)
    }
    return out
}

/** `listKanjiWords` (C-17, L-7): validation as the server's `KanjiWordsApi`, then [localOrForward]. */
internal suspend fun listKanjiWords(call: LocalCall): ShellResponse {
    val params = call.query("deck_id", "mine", "limit", "cursor")
    val literal = pathLiteral(call)
    val deckId = params.first("deck_id")?.let {
        runCatching { java.util.UUID.fromString(it).toString() }.getOrNull() ?: invalid("/deck_id", "deck_id must be a UUID.")
    }
    val mine = when (val v = params.first("mine")) {
        null, "false" -> false
        "true" -> true
        else -> invalid("/mine", "mine must be true or false, not \"$v\".")
    }
    val limit = params.first("limit")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..200 } ?: invalid("/limit", "limit must be an integer from 1 to 200.") } ?: 50
    val cursor = params.first("cursor")
    val env = DictEnv(call)
    return localOrForward(env, cursor, kanjiWords = true) { localKanjiWords(env, literal, deckId, mine, limit, cursor) }
}
