package kotobaya.dict

import io.ktor.http.HttpMethod
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotobaya.auth.AuthFixture
import kotobaya.auth.TokenKind
import kotobaya.server.AppDeps
import kotobaya.server.configureRequestLimits
import kotobaya.server.kotobayaModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/** BUG-7c: `listDictHeadwords`. */
class DictHeadwordsTest : AuthFixture() {
    private fun seed() = db.txBlocking { d ->
        d.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now())")
        for (seq in listOf(5L, 7L, 9L)) {
            val glosses = if (seq == 9L) "[]" else """[{"text":"g$seq"},{"text":"h$seq"}]"""
            d.execute(
                "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id) " +
                    "values (?, ?, ?, '[{\"text\":\"よみ\"}]', ?::jsonb, 1)",
                seq, "語$seq", "よみ$seq", """[{"gloss":$glosses}]""",
            )
        }
        d.execute("update ref.dict_entry set retired_release_id = 1 where seq = 9")
    }

    @Test
    fun mixedKnownAndUnknown() = app(extraHandlers = dictHandlers(db)) {
        seed()
        val t = mint(TokenKind.PERSONAL, listOf("read")).second
        val r = client.call(HttpMethod.Get, "/dict/headwords?seq=9,404,5,5,7", bearer = t)
        assertEquals(200, r.status.value)
        val items = r.json()["items"]
        assertEquals(listOf(5L, 7L, 9L), items.map { it["seq"].asLong() })
        assertEquals("語5", items[0]["headword"].asText())
        assertEquals("よみ5", items[0]["headword_reading"].asText())
        assertEquals(listOf("g5", "h5"), items[0]["glosses"].map { it.asText() })
        assertEquals(0, items[2]["glosses"].size()) // retired, no gloss: still returned
        assertEquals(0, client.call(HttpMethod.Get, "/dict/headwords?seq=404", bearer = t).json()["items"].size())
        for (q in listOf("", "?seq=", "?seq=1,,2", "?seq=1,", "?seq=0", "?seq=a", "?seq=-1", "?seq=1&seq=2", "?seq=" + (1..501).joinToString(","))) {
            assertEquals(422, client.call(HttpMethod.Get, "/dict/headwords$q", bearer = t).status.value, q)
        }
    }

    @Test
    fun fiveHundredSeqsFitTheNettyRequestLine() = app(extraHandlers = dictHandlers(db)) {
        seed()
        val t = mint(TokenKind.PERSONAL, listOf("read")).second
        val server = embeddedServer(Netty, configure = { connector { port = 0; host = "127.0.0.1" }; configureRequestLimits() }) {
            kotobayaModule(AppDeps(db, authenticator = auth.authenticator, handlers = dictHandlers(db)))
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val seqs = (1..500).map { 1_000_000L + it * 7 }.dropLast(3) + listOf(5L, 7L, 9L)
            val q = seqs.joinToString("%2C")
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/dict/headwords?seq=$q"))
                .header("Authorization", "Bearer $t").build()
            val res = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(req, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, res.statusCode(), res.body())
            assertEquals(3, kotobaya.server.json.readTree(res.body())["items"].size())
        } finally {
            server.stop(0, 0)
        }
    }
}
