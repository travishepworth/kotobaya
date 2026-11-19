package kotobaya.android.shell.api

import kotobaya.android.shell.api.OpClass.HIDDEN
import kotobaya.android.shell.api.OpClass.LOCAL
import kotobaya.android.shell.api.OpClass.LOCAL_OR_PROXY
import kotobaya.android.shell.api.OpClass.PROXY
import kotobaya.android.shell.api.OpClass.SHELL

/** How an operation is answered on the phone (SHELL.md 3.2); [SHELL] = an operation of `shell.yaml`. */
enum class OpClass { LOCAL, LOCAL_OR_PROXY, PROXY, HIDDEN, SHELL }

/** One operation of `openapi.yaml` (under `/api/v1`) or `shell.yaml` (under `/shell/v1`). */
data class ApiOperation(
    val operationId: String,
    /** Upper-case HTTP method. */
    val method: String,
    /** Full path template, e.g. `/api/v1/notes/{note_id}`. */
    val pathTemplate: String,
    val opClass: OpClass,
    /** For [HIDDEN]: `admin` or `import` when the operation needs that scope (rule P-4), else null. */
    val requiredScope: String? = null,
) {
    internal val segments: List<String> = pathTemplate.removePrefix("/").split('/')
}

/**
 * Every operation the dispatcher knows: the 123 of `openapi.yaml`, classified by `ops.json`, and the
 * 16 of `shell.yaml`. A copy of the contracts: `RouteTableTest` fails when it drifts from
 * `contracts/api/shell/ops.json`, `contracts/api/openapi.yaml` or `contracts/api/shell/shell.yaml`
 * (regenerate the rows from those files; never edit a class by hand).
 */
object ApiOperations {
    private fun op(id: String, method: String, path: String, c: OpClass, scope: String? = null) =
        ApiOperation(id, method, path, c, scope)

    val all: List<ApiOperation> = listOf(
        op("getHealth", "GET", "/api/v1/health", PROXY),
        op("getMeta", "GET", "/api/v1/meta", LOCAL),
        op("reportClientErrors", "POST", "/api/v1/client-errors", LOCAL),
        op("login", "POST", "/api/v1/auth/login", HIDDEN),
        op("logout", "POST", "/api/v1/auth/logout", HIDDEN),
        op("getPrincipal", "GET", "/api/v1/auth/session", LOCAL),
        op("listDevices", "GET", "/api/v1/devices", HIDDEN, "admin"),
        op("createPairing", "POST", "/api/v1/devices/pairings", HIDDEN, "admin"),
        op("pairDevice", "POST", "/api/v1/devices/pair", HIDDEN),
        op("getOwnDevice", "GET", "/api/v1/devices/self", PROXY),
        op("updateOwnDevice", "PATCH", "/api/v1/devices/self", PROXY),
        op("getDevice", "GET", "/api/v1/devices/{device_id}", HIDDEN, "admin"),
        op("updateDevice", "PATCH", "/api/v1/devices/{device_id}", HIDDEN, "admin"),
        op("revokeDevice", "DELETE", "/api/v1/devices/{device_id}", HIDDEN, "admin"),
        op("listTokens", "GET", "/api/v1/tokens", HIDDEN, "admin"),
        op("createToken", "POST", "/api/v1/tokens", HIDDEN, "admin"),
        op("getToken", "GET", "/api/v1/tokens/{token_id}", HIDDEN, "admin"),
        op("revokeToken", "DELETE", "/api/v1/tokens/{token_id}", HIDDEN, "admin"),
        op("listSettings", "GET", "/api/v1/settings", LOCAL),
        op("updateSettings", "PATCH", "/api/v1/settings", LOCAL),
        op("listPresets", "GET", "/api/v1/presets", LOCAL),
        op("createPreset", "POST", "/api/v1/presets", PROXY),
        op("getPreset", "GET", "/api/v1/presets/{preset_id}", LOCAL),
        op("updatePreset", "PATCH", "/api/v1/presets/{preset_id}", PROXY),
        op("deletePreset", "DELETE", "/api/v1/presets/{preset_id}", PROXY),
        op("optimizePreset", "POST", "/api/v1/presets/{preset_id}/optimize", HIDDEN, "import"),
        op("listNoteTypes", "GET", "/api/v1/note-types", LOCAL),
        op("createNoteType", "POST", "/api/v1/note-types", PROXY),
        op("getNoteType", "GET", "/api/v1/note-types/{note_type_id}", LOCAL),
        op("updateNoteType", "PATCH", "/api/v1/note-types/{note_type_id}", PROXY),
        op("deleteNoteType", "DELETE", "/api/v1/note-types/{note_type_id}", PROXY),
        op("searchDictionary", "GET", "/api/v1/dict/search", LOCAL_OR_PROXY),
        op("getDictEntry", "GET", "/api/v1/dict/entries/{seq}", LOCAL_OR_PROXY),
        op("listEntryExamples", "GET", "/api/v1/dict/entries/{seq}/examples", LOCAL_OR_PROXY),
        op("listDictHeadwords", "GET", "/api/v1/dict/headwords", LOCAL_OR_PROXY),
        op("getDictRelease", "GET", "/api/v1/dict/release", LOCAL_OR_PROXY),
        op("refreshDictionary", "POST", "/api/v1/dict/refresh", HIDDEN, "import"),
        op("listHotKanji", "GET", "/api/v1/kanji/hot", LOCAL),
        op("getKanji", "GET", "/api/v1/kanji/{literal}", LOCAL_OR_PROXY),
        op("listKanjiWords", "GET", "/api/v1/kanji/{literal}/words", LOCAL_OR_PROXY),
        op("listNotes", "GET", "/api/v1/notes", LOCAL),
        op("createNote", "POST", "/api/v1/notes", LOCAL),
        op("getNote", "GET", "/api/v1/notes/{note_id}", LOCAL),
        op("updateNote", "PATCH", "/api/v1/notes/{note_id}", LOCAL),
        op("deleteNote", "DELETE", "/api/v1/notes/{note_id}", LOCAL),
        op("listCards", "GET", "/api/v1/cards", LOCAL),
        op("createCard", "POST", "/api/v1/cards", PROXY),
        op("bulkCards", "POST", "/api/v1/cards/bulk", LOCAL),
        op("bulkScheduleCards", "POST", "/api/v1/cards/bulk/schedule", PROXY),
        op("getCard", "GET", "/api/v1/cards/{card_id}", LOCAL),
        op("updateCard", "PATCH", "/api/v1/cards/{card_id}", PROXY),
        op("deleteCard", "DELETE", "/api/v1/cards/{card_id}", PROXY),
        op("listCardReviews", "GET", "/api/v1/cards/{card_id}/reviews", LOCAL),
        op("getReviewQueue", "GET", "/api/v1/review/queue", LOCAL),
        op("answerReview", "POST", "/api/v1/review/answer", LOCAL),
        op("undoReview", "POST", "/api/v1/review/undo", LOCAL),
        op("createDrillSession", "POST", "/api/v1/drill/session", LOCAL),
        op("listDeckLists", "GET", "/api/v1/deck-lists", PROXY),
        op("listDecks", "GET", "/api/v1/decks", LOCAL),
        op("createDeck", "POST", "/api/v1/decks", LOCAL),
        op("getDeck", "GET", "/api/v1/decks/{deck_id}", LOCAL),
        op("updateDeck", "PATCH", "/api/v1/decks/{deck_id}", LOCAL),
        op("deleteDeck", "DELETE", "/api/v1/decks/{deck_id}", LOCAL),
        op("listDeckItems", "GET", "/api/v1/decks/{deck_id}/items", LOCAL),
        op("addDeckItems", "POST", "/api/v1/decks/{deck_id}/items", LOCAL),
        op("putDeckItem", "PUT", "/api/v1/decks/{deck_id}/items/{item_key}", LOCAL),
        op("removeDeckItem", "DELETE", "/api/v1/decks/{deck_id}/items/{item_key}", LOCAL),
        op("getDeckCoverage", "GET", "/api/v1/decks/{deck_id}/coverage", PROXY),
        op("listMedia", "GET", "/api/v1/media", PROXY),
        op("importMedia", "POST", "/api/v1/media/import", HIDDEN, "import"),
        op("importJpdbDeck", "POST", "/api/v1/media/import/jpdb", HIDDEN, "import"),
        op("importJpdbHistory", "POST", "/api/v1/import/jpdb/history", HIDDEN, "import"),
        op("listJpdbVocab", "GET", "/api/v1/import/jpdb/vocab", PROXY),
        op("putJpdbVocabResolution", "PUT", "/api/v1/import/jpdb/vocab/{jpdb_vid}/resolution", HIDDEN, "import"),
        op("clearJpdbVocabResolution", "DELETE", "/api/v1/import/jpdb/vocab/{jpdb_vid}/resolution", HIDDEN, "import"),
        op("getMedia", "GET", "/api/v1/media/{media_id}", PROXY),
        op("updateMedia", "PATCH", "/api/v1/media/{media_id}", PROXY),
        op("deleteMedia", "DELETE", "/api/v1/media/{media_id}", PROXY),
        op("reparseMedia", "POST", "/api/v1/media/{media_id}/reparse", HIDDEN, "import"),
        op("getMediaCoverage", "GET", "/api/v1/media/{media_id}/coverage", PROXY),
        op("listMediaSentences", "GET", "/api/v1/media/{media_id}/sentences", PROXY),
        op("listSentences", "GET", "/api/v1/sentences", PROXY),
        op("getSentence", "GET", "/api/v1/sentences/{sentence_id}", PROXY),
        op("listHotWords", "GET", "/api/v1/words/hot", LOCAL),
        op("listLeeches", "GET", "/api/v1/words/leeches", LOCAL),
        op("getWordStatus", "GET", "/api/v1/words/{seq}/status", LOCAL),
        op("setWordStatus", "PUT", "/api/v1/words/{seq}/status", LOCAL),
        op("clearWordStatus", "DELETE", "/api/v1/words/{seq}/status", LOCAL),
        op("listWordConfusables", "GET", "/api/v1/words/{seq}/confusables", PROXY),
        op("listConfusables", "GET", "/api/v1/confusables", PROXY),
        op("setConfusable", "PUT", "/api/v1/confusables/{note_a_id}/{note_b_id}/{reason}", PROXY),
        op("listGrammarPoints", "GET", "/api/v1/grammar", PROXY),
        op("createGrammarPoint", "POST", "/api/v1/grammar", PROXY),
        op("getGrammarPoint", "GET", "/api/v1/grammar/{grammar_point_id}", PROXY),
        op("updateGrammarPoint", "PATCH", "/api/v1/grammar/{grammar_point_id}", PROXY),
        op("deleteGrammarPoint", "DELETE", "/api/v1/grammar/{grammar_point_id}", PROXY),
        op("testGrammarRules", "POST", "/api/v1/grammar/{grammar_point_id}/rules/test", PROXY),
        op("listGrammarExamples", "GET", "/api/v1/grammar/{grammar_point_id}/examples", PROXY),
        op("putGrammarSpan", "PUT", "/api/v1/grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset}", PROXY),
        op("removeGrammarSpan", "DELETE", "/api/v1/grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset}", PROXY),
        op("getStatsSummary", "GET", "/api/v1/stats/summary", PROXY),
        op("getStatsHeatmap", "GET", "/api/v1/stats/heatmap", PROXY),
        op("getStatsRetention", "GET", "/api/v1/stats/retention", PROXY),
        op("getStatsForecast", "GET", "/api/v1/stats/forecast", PROXY),
        op("getStatsKnownWords", "GET", "/api/v1/stats/known-words", PROXY),
        op("getStatsCalibration", "GET", "/api/v1/stats/calibration", PROXY),
        op("parseText", "POST", "/api/v1/parse", PROXY),
        op("listParseOverrides", "GET", "/api/v1/parse-overrides", PROXY),
        op("putParseOverride", "PUT", "/api/v1/parse-overrides/{override_id}", PROXY),
        op("deleteParseOverride", "DELETE", "/api/v1/parse-overrides/{override_id}", PROXY),
        op("listJobs", "GET", "/api/v1/jobs", PROXY),
        op("getJob", "GET", "/api/v1/jobs/{job_id}", PROXY),
        op("cancelJob", "POST", "/api/v1/jobs/{job_id}/cancel", HIDDEN, "import"),
        op("synthesizeSpeech", "POST", "/api/v1/tts", PROXY),
        op("resolveSpeech", "POST", "/api/v1/tts/resolve", PROXY),
        op("queryBlobs", "POST", "/api/v1/blobs/query", PROXY),
        op("getBlob", "GET", "/api/v1/blobs/{hash}", LOCAL),
        op("putBlob", "PUT", "/api/v1/blobs/{hash}", PROXY),
        op("syncPush", "POST", "/api/v1/sync/push", HIDDEN),
        op("syncPull", "GET", "/api/v1/sync/pull", HIDDEN),
        op("exportAll", "GET", "/api/v1/export", PROXY),
        op("exportAnki", "GET", "/api/v1/export/anki", PROXY),
        op("streamEvents", "GET", "/api/v1/events", HIDDEN),
        op("ankiConnect", "POST", "/api/v1/ankiconnect", HIDDEN),
        op("getShellInfo", "GET", "/shell/v1/info", SHELL),
        op("pairShell", "POST", "/shell/v1/pair", SHELL),
        op("unpairShell", "POST", "/shell/v1/unpair", SHELL),
        op("getSyncStatus", "GET", "/shell/v1/sync", SHELL),
        op("syncNow", "POST", "/shell/v1/sync", SHELL),
        op("getDictInstall", "GET", "/shell/v1/dict", SHELL),
        op("startDictInstall", "POST", "/shell/v1/dict/install", SHELL),
        op("getStorage", "GET", "/shell/v1/storage", SHELL),
        op("updateStorage", "PATCH", "/shell/v1/storage", SHELL),
        op("getNotificationState", "GET", "/shell/v1/notifications", SHELL),
        op("updateNotificationPrefs", "PATCH", "/shell/v1/notifications", SHELL),
        op("requestNotificationPermission", "POST", "/shell/v1/notifications/permission", SHELL),
        op("getPushState", "GET", "/shell/v1/push", SHELL),
        op("updatePushState", "PATCH", "/shell/v1/push", SHELL),
        op("getAnkiConnectState", "GET", "/shell/v1/ankiconnect", SHELL),
        op("updateAnkiConnectState", "PATCH", "/shell/v1/ankiconnect", SHELL),
        op("getDisplayPrefs", "GET", "/shell/v1/display", SHELL),
        op("updateDisplayPrefs", "PATCH", "/shell/v1/display", SHELL),
    )

    val byId: Map<String, ApiOperation> = all.associateBy { it.operationId }

    /**
     * The operation for [method] + [path] (SHELL.md 3.1 step 2), with its decoded path parameters,
     * or null. A literal segment beats a parameter, compared left to right. A path parameter never
     * matches an empty segment.
     */
    fun match(method: String, path: String): Pair<ApiOperation, Map<String, String>>? {
        val segs = path.removePrefix("/").split('/')
        var best: ApiOperation? = null
        var bestKey: List<Boolean>? = null
        for (op in all) {
            if (op.method != method || op.segments.size != segs.size) continue
            var ok = true
            val key = ArrayList<Boolean>(segs.size)
            for (i in segs.indices) {
                val t = op.segments[i]
                val isParam = t.startsWith("{") && t.endsWith("}")
                if (isParam) {
                    if (segs[i].isEmpty()) { ok = false; break }
                } else if (t != segs[i]) {
                    ok = false; break
                }
                key.add(!isParam)
            }
            if (!ok) continue
            if (bestKey == null || isBetter(key, bestKey)) {
                best = op
                bestKey = key
            }
        }
        val op = best ?: return null
        val params = LinkedHashMap<String, String>()
        for (i in segs.indices) {
            val t = op.segments[i]
            if (t.startsWith("{")) params[t.substring(1, t.length - 1)] = percentDecode(segs[i])
        }
        return op to params
    }

    private fun isBetter(a: List<Boolean>, b: List<Boolean>): Boolean {
        for (i in a.indices) if (a[i] != b[i]) return a[i]
        return false
    }
}
