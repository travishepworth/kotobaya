package kotobaya.android.shell.api.mining

import kotobaya.android.dict.DictGapCache
import kotobaya.android.dict.DictLookup
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.GapRef
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.Resolved
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellHeaders
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.timestamp
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.apply.LearningTables

/** What every dictionary handler reads (SHELL.md L-7): the one lookup, the stores behind it, and the server. */
internal class DictEnv(private val call: LocalCall) {
    val store: SqlDelightDeviceStore = call.services.get()
    val lookup: DictLookup = call.services.get()
    val cache: DictGapCache = call.services.get()
    val dict: InstalledDictionary = call.services.get()
    val pkg: DictPackage = packageOf(call.services.get<DictStore>())
    private val upstream: Upstream = call.services.get()

    /** A package is installed (L-7 step 1 can answer). */
    val installed: Boolean get() = dict.isAvailable() && runCatching { dict.info() }.getOrNull() != null

    /** L-7 "Authoritative": scope `all`, not older than the last known server release. */
    val authoritative: Boolean get() = lookup.isAuthoritative()

    /** The installed release id (cursors of a replaced package are refused), 0 when none. */
    val releaseId: Long get() = runCatching { dict.info()?.releaseId }.getOrNull() ?: 0L

    fun <T> read(block: (LearningTables) -> T): T = store.read { block(it.learning) }

    /**
     * Forwards the request (rules P-1 … P-3) and returns the answer, or null when the forward failed as in P-1
     * (`503 shell_offline`: offline, or the server could not be reached).
     */
    suspend fun forward(): ShellResponse? {
        val r = upstream.forward(call.request, call.operation, call.requestId)
        return if (isShellOffline(r)) null else r
    }

    /** L-7 "Negative results": the server answered `404` for [ref] under the current last known release. */
    fun standingNegative(ref: GapRef): Boolean {
        val row = cache.row(ref) ?: return false
        return row.body == null && row.releaseId == (cache.serverReleaseId() ?: 0L)
    }

    companion object {
        fun isShellOffline(r: ShellResponse): Boolean =
            r.status == 503 && r.headers[ShellHeaders.LOCAL] == "1" && problemCode(r) == "shell_offline"

        private fun problemCode(r: ShellResponse): String? =
            ((r.body?.let { DefaultCanonicalJson.decode(it).value } as? JsonObject)?.members?.get("code") as? JsonString)?.value
    }
}

internal fun dictMissing(what: String): Nothing =
    throw ProblemException(Problems.shellDictMissing("$what is not on this device; connect to look it up."))

internal fun invalid(pointer: String, detail: String, code: String = "invalid_value"): Nothing =
    throw ProblemException(Problems.validationFailed(pointer, code, detail))

/** `{seq}` path parameter as the server reads it: a positive integer, else `422` at `/seq`. */
private fun pathSeq(call: LocalCall): Long =
    call.path("seq").toLongOrNull()?.takeIf { it > 0 } ?: invalid("/seq", "seq must be a positive integer.")

/** `{literal}` path parameter: exactly one code point, else `422` at `/literal`. */
internal fun pathLiteral(call: LocalCall): String =
    call.path("literal").takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) == 1 }
        ?: invalid("/literal", "literal must be exactly one character.")

/**
 * A forwarded `2xx` page (`searchDictionary`, `listKanjiWords`) with each item's overlay recomputed from the replica
 * (L-7 "Forward and merge"); anything else is passed through unchanged.
 */
internal fun DictEnv.overlaid(r: ShellResponse, kanjiWords: Boolean): ShellResponse {
    if (r.status !in 200..299) return r
    val body = r.body?.let { DefaultCanonicalJson.decode(it).value } as? JsonObject ?: return r
    val items = body.members["items"] as? JsonArray ?: return r
    val out = read { t -> items.items.map { (it as? JsonObject)?.let { o -> DictViews.withOverlay(t, o, kanjiWords) } ?: it } }
    val m = LinkedHashMap(body.members)
    m["items"] = JsonArray(out)
    return r.copy(body = DefaultCanonicalJson.encode(JsonObject(m)))
}

/** `getDictEntry` (L-7): steps 1-2, else the server when online (cached), else `404` / `503 shell_dict_missing`. */
internal suspend fun getDictEntry(call: LocalCall): ShellResponse {
    call.query()
    val seq = pathSeq(call)
    val env = DictEnv(call)
    return when (val r = env.lookup.resolveEntry(seq)) {
        is Resolved.Found -> ShellResponse.json(env.read { DictViews.entry(it, r.value) })
        Resolved.NotFound -> throw ProblemException(Problems.notFound("No dictionary entry $seq."))
        Resolved.NotOnDevice -> dictMissing("Dictionary entry $seq")
    }
}

/** `getKanji` (L-7), as [getDictEntry]. */
internal suspend fun getKanji(call: LocalCall): ShellResponse {
    call.query()
    val literal = pathLiteral(call)
    val env = DictEnv(call)
    return when (val r = env.lookup.resolveKanji(literal)) {
        is Resolved.Found -> ShellResponse.json(env.read { DictViews.kanji(it, r.value) })
        Resolved.NotFound -> throw ProblemException(Problems.notFound("No kanji $literal."))
        Resolved.NotOnDevice -> dictMissing("Kanji $literal")
    }
}

/**
 * `listDictHeadwords` (C-18, L-7): every requested `seq` by steps 1-2. All found, or the rest are known misses
 * (authoritative package, standing negative results) → local `200` with the missing ones omitted. Otherwise the whole
 * request is forwarded; offline → `503 shell_dict_missing`.
 */
internal suspend fun listDictHeadwords(call: LocalCall): ShellResponse {
    val q = call.query("seq")
    val raw = q.all("seq")
    if (raw.isEmpty()) invalid("/seq", "seq is required.", "required")
    val bad = "seq values must be integers >= 1, comma-separated."
    if (raw.size != 1) invalid("/seq", "seq must be given exactly once.")
    val parts = raw[0].split(',')
    if (parts.size > 500) invalid("/seq", "seq holds at most 500 values.")
    val seqs = parts.map { p ->
        if (p.isEmpty() || p.length > 19 || !p.all { it in '0'..'9' }) invalid("/seq", bad)
        p.toLongOrNull()?.takeIf { it >= 1 } ?: invalid("/seq", bad)
    }.distinct().sorted()
    val env = DictEnv(call)
    val found = seqs.mapNotNull { env.lookup.entry(it) }
    val missing = seqs.toSet() - found.map { it.seq }.toSet()
    val known = missing.isEmpty() || env.authoritative || missing.all { env.standingNegative(GapRef.entry(it)) }
    if (!known) return env.forward() ?: dictMissing("Some of these dictionary entries are")
    return ShellResponse.json(jsonObj("items" to JsonArray(found.map(DictViews::headword))))
}

/**
 * `getDictRelease` (L-7): an installed package describes itself (`artifacts` empty: what the server offers is the
 * installer's business, `getDictInstall`). None: forwarded (a `200` also updates the last known server release);
 * offline → `503 shell_dict_missing`.
 */
internal suspend fun getDictRelease(call: LocalCall): ShellResponse {
    call.query()
    val env = DictEnv(call)
    val rel = env.pkg.release()
    if (rel == null) {
        val r = env.forward() ?: throw ProblemException(Problems.shellDictMissing("No dictionary is installed; connect or install one."))
        if (r.status == 200) {
            val id = ((r.body?.let { DefaultCanonicalJson.decode(it).value } as? JsonObject)?.members?.get("id") as? JsonNumber)?.value?.toLong()
            id?.let { runCatching { env.cache.recordServerRelease(it) } }
        }
        return r
    }
    val sources: JsonValue = runCatching { DefaultCanonicalJson.decode(rel.sources).value }.getOrNull() as? JsonObject ?: JsonObject(emptyMap())
    return ShellResponse.json(
        jsonObj(
            "id" to JsonNumber(rel.id.toDouble()),
            "version" to JsonString(rel.version),
            "built_at" to JsonString(timestamp(rel.builtAtMs)),
            // the device does not know when the server loaded it; the build time is the closest it has
            "loaded_at" to JsonString(timestamp(rel.builtAtMs)),
            "sources" to sources,
            "freq_lists" to JsonArray(
                env.pkg.freqLists().map {
                    jsonObj(
                        "id" to JsonString(it.id), "title" to JsonString(it.title),
                        "priority" to JsonNumber(it.priority.toDouble()), "entry_count" to JsonNumber(it.entryCount.toDouble()),
                    )
                },
            ),
            "artifacts" to JsonArray(emptyList()),
        ),
    )
}

/**
 * `listEntryExamples` (L-7, SHELL.md 4.1): the entry by the lookup as `getDictEntry` (same `404` / `503`); found →
 * `media` = sentences of media installed on this device, `tatoeba` = the package's pairs (both may be empty).
 */
internal suspend fun listEntryExamples(call: LocalCall): ShellResponse {
    val q = call.query("limit")
    val seq = pathSeq(call)
    val limit = q.first("limit")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..50 } ?: invalid("/limit", "limit must be an integer from 1 to 50.") } ?: 10
    val env = DictEnv(call)
    when (env.lookup.resolveEntry(seq)) {
        is Resolved.Found -> Unit
        Resolved.NotFound -> throw ProblemException(Problems.notFound("No dictionary entry $seq."))
        Resolved.NotOnDevice -> dictMissing("Dictionary entry $seq")
    }
    val media = MediaSentences.forEntry(env.cache, env.store, seq, limit)
    val tatoeba = env.pkg.examples(seq, limit).map {
        jsonObj("id" to JsonNumber(it.id.toDouble()), "text" to JsonString(it.text), "translation" to JsonString(it.translation))
    }
    return ShellResponse.json(jsonObj("media" to JsonArray(media), "tatoeba" to JsonArray(tatoeba)))
}
