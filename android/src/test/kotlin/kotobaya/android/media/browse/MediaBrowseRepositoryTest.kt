package kotobaya.android.media.browse

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotobaya.android.net.ApiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class MediaBrowseRepositoryTest {
    private val server = MockWebServer()
    private val dir = Files.createTempDirectory("browse").toFile()
    private var online = true
    private var clock = 1000L
    private val repo = MediaBrowseRepository(
        ApiClient({ server.url("/api/v1").toString().trimEnd('/') }, { "t" }, { online }),
        FileBrowseCache(dir),
    ) { clock }

    @AfterTest
    fun tearDown() { server.shutdown(); dir.deleteRecursively() }

    private val body = """{"deck_id":"d","segment_id":null,"hide":[],
        "unique":{"total":10,"known":6,"learning":2,"unknown":2},
        "tokens":{"total":100,"known":80,"learning":10,"unknown":10,"unmapped":0,"known_pct":80.0},
        "ignored_count":0,"curve":[{"words":0,"known_pct":80.0},{"words":10,"known_pct":90.0}],
        "segments":[{"segment_id":"s","ordinal":0,"title":"Ep 1","unique_total":10,"unique_unknown":2,"token_total":100,"known_pct":80.0}],
        "difficulty":null,"computed_at":"x"}"""

    @Test
    fun servesCachedCoverageWhenClientFails() {
        server.enqueue(MockResponse().setBody(body))
        val first = assertIs<Loaded.Data<CoverageView>>(runBlocking { repo.coverage("m1") })
        assertEquals(false, first.fromCache)
        assertEquals(80.0, first.value.tokenKnownPct)
        assertEquals("Ep 1", first.value.segments.single().title)

        online = false
        clock = 5000L
        val second = assertIs<Loaded.Data<CoverageView>>(runBlocking { repo.coverage("m1") })
        assertTrue(second.fromCache)
        assertEquals(1000L, second.updatedAtMs)
        assertEquals(2, second.value.curve.size)
    }

    @Test
    fun needsConnectionWithNoCache() {
        online = false
        assertEquals(Loaded.NeedsConnection, runBlocking { repo.coverage("m2") })
    }

    @Test
    fun sentencesSendEncodedFilter() {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"id":"x","text":"パンを食べる。","origin":"generated","unknown_count":1,"audio_blob_hash":null}],"next_cursor":null}""",
            ),
        )
        val r = assertIs<Loaded.Data<List<SentenceView>>>(runBlocking { repo.sentences("m3") })
        assertTrue(r.value.single().isGenerated)
        assertTrue(server.takeRequest().path!!.contains("filter=i%2B1"))
    }
}
