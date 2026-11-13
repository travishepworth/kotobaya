package kotobaya.coverage

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpMethod
import kotobaya.auth.AuthFixture
import kotobaya.auth.TokenKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T404c acceptance (mandatory): on both `fixtures/coverage` decks the response equals the hand-computed contract
 * numbers exactly, and unique/token coverage is within 3 points of the recorded jpdb-style numbers.
 */
class CoverageFixtureAgreementTest : AuthFixture() {
    private val data = CoverageData(db)
    private val coverage = CoverageModule(db)

    private fun reader() = mint(TokenKind.PERSONAL, listOf("read")).second

    private fun pct(part: Int, whole: Int): Double = if (whole == 0) 0.0 else part * 100.0 / whole

    private fun assertAgreement(fixture: JsonNode, got: JsonNode) {
        val e = fixture["expected"]
        assertEquals(fixture["media_id"].asText(), got["deck_id"].asText())
        assertTrue(got["segment_id"].isNull)
        assertEquals(e["hide"], got["hide"])
        assertEquals(e["unique"], got["unique"])
        assertEquals(e["tokens"], got["tokens"])
        assertEquals(e["ignored_count"], got["ignored_count"])
        assertEquals(e["curve"], got["curve"])
        assertEquals(e["difficulty"], got["difficulty"])
        val segs = got["segments"]
        assertEquals(e["segments"].size(), segs.size())
        e["segments"].forEachIndexed { i, s ->
            val g = segs[i]
            assertEquals(fixture["segments"][i]["id"].asText(), g["segment_id"].asText())
            for (k in listOf("ordinal", "title", "unique_total", "unique_unknown", "token_total", "known_pct")) assertEquals(s[k], g[k], "segment $i $k")
        }
        assertTrue(got["computed_at"].asText().endsWith("Z"))

        val jpdb = fixture["jpdb"]
        val uniquePct = pct(got["unique"]["known"].asInt(), got["unique"]["total"].asInt())
        assertTrue(abs(uniquePct - jpdb["unique_known_pct"].asDouble()) <= 3.0, "unique $uniquePct vs jpdb ${jpdb["unique_known_pct"]}")
        val tokenPct = got["tokens"]["known_pct"].asDouble()
        assertTrue(abs(tokenPct - jpdb["token_known_pct"].asDouble()) <= 3.0, "tokens $tokenPct vs jpdb ${jpdb["token_known_pct"]}")
    }

    @Test
    fun bothFixtureDecksMatchHandComputedNumbersExactlyAndJpdbWithinThreePoints() = app(extraHandlers = coverage.handlers) {
        val token = reader()
        val a = data.load("deck-a-show.json")
        val b = data.load("deck-b-novel.json")
        for (f in listOf(a, b)) {
            val r = client.call(HttpMethod.Get, "/media/${f["media_id"].asText()}/coverage", bearer = token)
            assertEquals(200, r.status.value, r.json().toString())
            assertAgreement(f, r.json())
        }
    }

    @Test
    fun aMediaDeckAnswersExactlyLikeItsMedia() = app(extraHandlers = coverage.handlers) {
        val token = reader()
        val a = data.load("deck-a-show.json")
        data.load("deck-b-novel.json")
        val id = a["media_id"].asText()
        data.deck(id, "media", mediaId = id)
        val r = client.call(HttpMethod.Get, "/decks/$id/coverage", bearer = token)
        assertEquals(200, r.status.value, r.json().toString())
        assertAgreement(a, r.json())
    }
}
