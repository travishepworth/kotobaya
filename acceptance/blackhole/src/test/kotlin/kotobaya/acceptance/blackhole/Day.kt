package kotobaya.acceptance.blackhole

import java.time.Instant
import kotlin.random.Random
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.store.UuidV7Source

/**
 * One scripted day on one phone, through [LocalApi] only: open Home, review [answersTarget] cards (new cards from deck
 * entries by Keep going when the goal is reached), and every [EVERY] answers a search, dictionary entries, a note edit,
 * a bulk tag and a background sync attempt. The device clock is the virtual clock (20 s per answer; waits for a
 * learning card jump to its due time).
 *
 * Every UI request is measured against the black-hole network: it must not call the upstream, wait for the network or
 * touch the sync transport. Every queue response goes through [LoopInvariants].
 */
class Day(private val phone: SimPhone, private val answersTarget: Int, private val syncMidDay: Boolean, seed: Int) {
    /** Findings: UI requests that waited for or used the network, invariant violations, unexpected statuses. */
    val problems = ArrayList<String>()
    val loopViolations = ArrayList<String>()
    var uiRequests = 0
        private set
    var queueResponses = 0
        private set
    var keepGoingPresses = 0
        private set
    var midDaySyncs = 0
        private set

    /** Ids of the review answers the phone accepted (200). */
    val answered = ArrayList<String>()
    private val reviewIds = UuidV7Source(Random(seed))
    private val recentCards = ArrayList<String>()
    private var keepGoingTarget: Int? = null
    private var nonLocal = 0

    private fun json(r: ShellResponse): JsonObject = ParityJson.obj(ParityJson.parse(r.body ?: error("no body (status ${r.status})")))

    /** One UI-path request with the zero-network check. [allowed]: statuses that are fine (default 200/204). */
    private fun ui(label: String, method: String, path: String, query: String = "", body: JsonValue? = null, allowed: Set<Int> = setOf(200, 204)): ShellResponse {
        val calls = phone.net.uiCalls.size
        val waited = phone.net.uiWaitedMs
        val syncReqs = phone.net.syncRequests
        val res = phone.request(method, path, query, body)
        uiRequests++
        if (phone.net.uiCalls.size != calls) problems += "$label: reached the network: ${phone.net.uiCalls.drop(calls)}"
        if (phone.net.uiWaitedMs != waited) problems += "$label: waited ${phone.net.uiWaitedMs - waited} ms for the network"
        if (phone.net.syncRequests != syncReqs) problems += "$label: used the sync transport"
        val uncached = res.status == 503 && res.body?.contains("shell_uncached") == true
        if (res.status !in allowed && !uncached) problems += "$label: status ${res.status} ${res.body?.take(200)}"
        if (res.forwarded) problems += "$label: answer came from the server"
        return res
    }

    private fun queue(query: String): JsonObject {
        val q = buildString {
            append(query)
            keepGoingTarget?.let { append("&keep_going=true&keep_going_new_target=$it") }
        }.trimStart('&')
        val res = ui("queue $q", "GET", "/api/v1/review/queue", q)
        val body = json(res)
        queueResponses++
        loopViolations += LoopInvariants.check(body).map { "${phone.deviceId.takeLast(2)} $q: $it" }
        return body
    }

    fun home() {
        queue("limit=10")
        ui("decks", "GET", "/api/v1/decks")
        ui("stats summary", "GET", "/api/v1/stats/summary")
        ui("heatmap", "GET", "/api/v1/stats/heatmap", "from=2026-10-01&to=2026-11-20")
        ui("retention", "GET", "/api/v1/stats/retention", "from=2026-10-01&to=2026-11-20")
        ui("hot words", "GET", "/api/v1/words/hot")
        ui("deck lists (cached)", "GET", "/api/v1/deck-lists")
    }

    private fun obj(vararg m: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*m))

    private fun str(s: String) = JsonString(s)

    private fun answer(item: JsonObject, i: Int) {
        val cardId = ParityJson.str(ParityJson.at(item, "/card/id"))
        val noteId = ParityJson.str(ParityJson.at(item, "/card/note_id"))
        ui("speech", "POST", "/api/v1/tts/resolve", body = obj("items" to JsonArray(listOf(obj("note_id" to str(noteId))))), allowed = setOf(200, 202, 204, 404))
        val rating = when {
            i % 5 == 0 -> 1
            i % 7 == 0 -> 4
            else -> 3
        }
        val button = mapOf(1 to "again", 3 to "good", 4 to "easy").getValue(rating)
        val id = reviewIds.newOpId(phone.clock.nowMs())
        val res = ui(
            "answer", "POST", "/api/v1/review/answer",
            body = obj("id" to str(id), "card_id" to str(cardId), "rating" to JsonNumber(rating.toDouble()), "duration_ms" to JsonNumber(4200.0), "utc_offset_min" to JsonNumber(0.0), "button" to str(button)),
            // a card that a pull deleted or merged away is refused with 409/410 and dropped (SHELL.md L-9 note)
            allowed = setOf(200, 204, 409, 410),
        )
        if (res.status in setOf(200, 204)) {
            answered += id
            recentCards += cardId
        }
        phone.clock.now += 20_000
    }

    /** The scripted side trips of a day. */
    private fun sideTrips() {
        val noteId = recentCards.lastOrNull()?.let { c ->
            ParityJson.str(ParityJson.at(json(ui("card", "GET", "/api/v1/cards/$c")), "/note_id"))
        }
        // search
        ui("search cards", "GET", "/api/v1/cards", "query=deck%3A*&limit=20")
        ui("search tag", "GET", "/api/v1/cards", "query=tag%3Ablackhole&limit=20")
        ui("search notes", "GET", "/api/v1/notes", "limit=20")
        // dictionary: a search, entries (installed and missing), examples
        val hits = json(ui("dict search", "GET", "/api/v1/dict/search", "q=%E9%A3%9F"))
        val first = (ParityJson.at(hits, "/entries") as? JsonArray)?.items?.firstOrNull()?.let { ParityJson.long(ParityJson.at(it, "/seq")) }
        ui("dict miss search", "GET", "/api/v1/dict/search", "q=zzzzzz")
        if (first != null) {
            ui("dict entry", "GET", "/api/v1/dict/entries/$first")
            ui("dict examples", "GET", "/api/v1/dict/entries/$first/examples", allowed = setOf(200, 404))
        }
        ui("dict missing entry", "GET", "/api/v1/dict/entries/9999999", allowed = setOf(200, 404))
        // edit a note
        if (noteId != null) {
            ui("edit note", "PATCH", "/api/v1/notes/$noteId", body = obj("fields" to obj("notes" to str("edited at ${phone.clock.nowMs()}"))))
            ui("edit note tags", "PATCH", "/api/v1/notes/$noteId", body = obj("add_tags" to JsonArray(listOf(str("edited")))))
        }
        // bulk tag the cards just answered
        val ids = recentCards.takeLast(5).distinct()
        if (ids.isNotEmpty()) {
            val action = obj("type" to str("add_tags"), "tags" to JsonArray(listOf(str("blackhole"))))
            ui("bulk tag", "POST", "/api/v1/cards/bulk", body = obj("card_ids" to JsonArray(ids.map { str(it) }), "action" to action), allowed = setOf(200, 204, 409))
        }
        ui("search tag again", "GET", "/api/v1/cards", "query=tag%3Ablackhole&limit=20")
    }

    /** A background sync attempt; its outcome is the network's business. */
    private fun backgroundSync() {
        midDaySyncs++
        val r = phone.sync()
        if (r !is CycleResult.Completed) nonLocal++
    }

    fun run() {
        home()
        var guard = 0
        while (answered.size < answersTarget && guard++ < 2_000) {
            val q = queue("limit=10")
            val items = ParityJson.arr(ParityJson.at(q, "/items")).map(ParityJson::obj)
            if (items.isNotEmpty()) {
                answer(items.first(), answered.size + 1)
                val n = answered.size
                if (n > 0 && n % EVERY == 0) {
                    sideTrips()
                    if (syncMidDay) backgroundSync()
                }
                continue
            }
            when (val phase = ParityJson.str(ParityJson.at(q, "/loop/phase"))) {
                "waiting" -> {
                    val due = ParityJson.at(q, "/loop/later_today/next_due_at")
                    if (due == null || due == JsonNull) break
                    phone.clock.now = maxOf(phone.clock.now + 1_000, Instant.parse(ParityJson.str(due)).toEpochMilli() + 1_000)
                }
                "goal_reached", "done_for_today" -> {
                    val available = (ParityJson.at(q, "/loop/keep_going_available") as? JsonBool)?.value == true
                    if (!available) break
                    // Keep going: one more goal-sized batch of new cards (ADR-051)
                    val today = ParityJson.long(ParityJson.at(q, "/loop/new/today"))
                    val goal = ParityJson.long(ParityJson.at(q, "/loop/new/goal"))
                    keepGoingTarget = (today + maxOf(1, goal)).toInt()
                    keepGoingPresses++
                    // I4: pressing it must serve a card now (no dead end)
                    val pressed = queue("limit=10")
                    if (ParityJson.arr(ParityJson.at(pressed, "/items")).isEmpty()) {
                        loopViolations += "I4: keep_going_available but pressing Keep going served nothing (target $keepGoingTarget)"
                    }
                }
                else -> {
                    loopViolations += "stopped in phase $phase with no items after ${answered.size} answers"
                    break
                }
            }
        }
        sideTrips()
    }

    companion object {
        const val EVERY = 20
    }
}
