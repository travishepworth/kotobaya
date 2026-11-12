package kotobaya.dict

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotobaya.server.AppDeps
import kotobaya.server.json
import kotobaya.server.kotobayaModule
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictSearchTest {
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val db = DictFixture.db
        application { kotobayaModule(AppDeps(db, handlers = dictHandlers(db))) }
        block()
    }

    private suspend fun ApplicationTestBuilder.search(query: String): Pair<HttpResponse, JsonNode> {
        val r = client.get("/api/v1/dict/search?$query")
        return r to json.readTree(r.bodyAsText())
    }

    private fun JsonNode.seqs() = this["items"].map { it["seq"].asLong() }

    @Test
    fun fiftyFixtureQueriesReturnTheExpectedTopResult() = withApp {
        val spec = json.readTree(javaClass.getResourceAsStream("/dict/queries.json")!!)
        assertEquals(DictFixture.retired, spec["retired"].map { it.asLong() }, "queries.json and the fixture agree on retired entries")
        val queries = spec["queries"]
        assertEquals(50, queries.size())
        val failures = mutableListOf<String>()
        for (q in queries) {
            val (resp, body) = search("q=${enc(q["q"].asText())}")
            val top = body["items"]?.firstOrNull()?.get("seq")?.asLong()
            val want = q["expect"].takeIf { !it.isNull }?.asLong()
            if (resp.status.value != 200 || top != want) {
                failures += "${q["q"].asText()} (${q["kind"].asText()}): expected $want, got $top (HTTP ${resp.status.value})"
            }
            for (retiredSeq in DictFixture.retired) {
                if (retiredSeq in (body["items"]?.map { it["seq"].asLong() } ?: emptyList())) {
                    failures += "${q["q"].asText()}: retired $retiredSeq returned"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun emptyAndBlankQueryIs422AndBadParamsToo() = withApp {
        for (query in listOf("q=", "q=%20%20", "", "q=a&limit=0", "q=a&limit=501", "q=a&common_only=maybe", "q=a&cursor=garbage")) {
            val (resp, body) = search(query)
            assertEquals(422, resp.status.value, query)
            assertEquals("validation_failed", body["code"].asText(), query)
            assertTrue(body["errors"].size() >= 1, query)
        }
        assertEquals(422, search("q=${"あ".repeat(101)}").first.status.value)
    }

    @Test
    fun noMatchIsAnEmptyPage() = withApp {
        val (resp, body) = search("q=${enc("ぬぬぬぬぬ")}")
        assertEquals(200, resp.status.value)
        assertEquals(0, body["items"].size())
        assertTrue(body["next_cursor"].isNull)
    }

    @Test
    fun retiredEntriesAreNeverSearchedButStillResolvable() = withApp {
        for (q in listOf("明日", "あした", "tomorrow", "あし")) {
            assertTrue(1584660L !in search("q=${enc(q)}&limit=500").second.seqs(), q)
        }
        val e = json.readTree(client.get("/api/v1/dict/entries/1584660").bodyAsText())
        assertEquals(true, e["retired"].asBoolean())
        assertEquals("明日", e["headword"].asText())
    }

    @Test
    fun pagingWalksTheWholeResultOnceInOrderAndRejectsChangedParams() = withApp {
        val expected = search("q=${enc("お")}&limit=500").second.seqs()
        assertTrue(expected.size > 25, "fixture has enough お-entries to page: ${expected.size}")
        assertEquals(expected.size, expected.toSet().size)
        val walked = mutableListOf<Long>()
        var cursor: String? = null
        var pages = 0
        do {
            val body = search("q=${enc("お")}&limit=10" + (cursor?.let { "&cursor=${enc(it)}" } ?: "")).second
            assertTrue(body["items"].size() <= 10)
            walked += body.seqs()
            cursor = body["next_cursor"].takeIf { !it.isNull }?.asText()
            pages++
        } while (cursor != null)
        assertEquals(expected, walked)
        assertEquals((expected.size + 9) / 10, pages)

        val first = search("q=${enc("お")}&limit=10").second["next_cursor"].asText()
        assertEquals(422, search("q=${enc("か")}&limit=10&cursor=${enc(first)}").first.status.value)
        assertEquals(422, search("q=${enc("お")}&common_only=true&limit=10&cursor=${enc(first)}").first.status.value)
    }

    @Test
    fun resultOrderIsExactThenPrefixThenRestThenFreqThenSeq() = withApp {
        // ねこ: 猫 (reading ねこ, exact) before ねこま (prefix).
        assertEquals(listOf(1467640L, 2698030L), search("q=${enc("ねこ")}").second.seqs().take(2))
        // Several exact matches: ascending freq_rank (here ranked by seq); 明日 (1584660) is retired in the fixture.
        assertEquals(2857350L, search("q=${enc("あした")}").second.seqs().first())
    }

    @Test
    fun commonOnlyFiltersAndOverlayDefaultsAreEmpty() = withApp {
        val body = search("q=${enc("の")}&common_only=true&limit=500").second
        assertTrue(body["items"].size() > 0 && body["items"].all { it["is_common"].asBoolean() })
        val item = search("q=${enc("ねこ")}").second["items"][0]
        assertEquals("new", item["status"].asText())
        assertTrue(item["note_id"].isNull)
        assertEquals(false, item["retired"].asBoolean())
        assertEquals("猫", item["headword"].asText())
        assertEquals("cat (esp. the domestic cat, Felis catus)", item["glosses"][0].asText())
    }

    @Test
    fun statusOverlayComesFromWordStatus() = withApp {
        DictFixture.db.txBlocking {
            it.execute(
                "insert into app.word_status (entry_seq, status, auto_status, updated_at) " +
                    "values (1473740, 'learning', 'learning', now()) on conflict do nothing",
            )
        }
        assertEquals("learning", search("q=${enc("かう")}").second["items"][0]["status"].asText())
        assertEquals("learning", json.readTree(client.get("/api/v1/dict/entries/1473740").bodyAsText())["status"].asText())
    }

    @Test
    fun entryEndpoint() = withApp {
        val r = client.get("/api/v1/dict/entries/1206730")
        assertEquals(200, r.status.value)
        val e = json.readTree(r.bodyAsText())
        assertEquals("学校", e["headword"].asText())
        assertEquals(listOf("学", "校", "學"), e["kanji"].map { it.asText() })
        assertEquals("がっこう", e["readings"][0]["text"].asText())
        assertEquals(0, e["freqs"].size())
        assertEquals(0, e["pitch"].size())
        assertEquals(false, e["retired"].asBoolean())
        assertEquals("new", e["status"].asText())
        assertTrue(e["note_id"].isNull)
        assertEquals(404, client.get("/api/v1/dict/entries/999999999").status.value)
        assertEquals(422, client.get("/api/v1/dict/entries/abc").status.value)
    }

    @Test
    fun kanjiEndpoint() = withApp {
        val r = client.get("/api/v1/kanji/${enc("学")}")
        assertEquals(200, r.status.value)
        val k = json.readTree(r.bodyAsText())
        assertEquals("学", k["literal"].asText())
        assertTrue(k["meanings"].size() > 0)
        assertTrue(k["note_id"].isNull)
        assertEquals(404, client.get("/api/v1/kanji/${enc("龘")}").status.value)
        assertEquals(422, client.get("/api/v1/kanji/${enc("学校")}").status.value)
    }

    @Test
    fun romajiConversion() {
        val cases = mapOf(
            "taberu" to "たべる", "kanji" to "かんじ", "konnichiwa" to "こんにちわ", "sensei" to "せんせい",
            "gakkou" to "がっこう", "kitte" to "きって", "matcha" to "まっちゃ", "shinbun" to "しんぶん",
            "nn" to "ん", "kan'i" to "かんい", "kannai" to "かんない", "ja" to "じゃ", "kyou" to "きょう",
            "tsunami" to "つなみ", "fuji" to "ふじ", "chotto" to "ちょっと", "ra-men" to "らーめん",
            "TOUKYOU" to "とうきょう", "wo" to "を", "n" to "ん", "shi" to "し", "ji" to "じ", "kannna" to "かんな",
        )
        for ((r, k) in cases) assertEquals(k, Romaji.toHiragana(r), r)
        assertNull(Romaji.toHiragana("teacher!x"))
        assertNull(Romaji.toHiragana("l"))
    }
}
