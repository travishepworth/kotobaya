package kotobaya.android.shell.api.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.cards.LocalWritesSupport.assertOpsValidAndConverge
import kotobaya.android.shell.api.cards.LocalWritesSupport.call
import kotobaya.android.shell.api.cards.LocalWritesSupport.device
import kotobaya.android.shell.api.cards.LocalWritesSupport.items
import kotobaya.android.shell.api.cards.LocalWritesSupport.json
import kotobaya.android.shell.api.cards.LocalWritesSupport.mintedTypes
import kotobaya.android.shell.api.cards.LocalWritesSupport.problem
import kotobaya.android.shell.api.cards.LocalWritesSupport.rows
import kotobaya.android.shell.api.confusables.RemoteOps
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A8b: `updateMedia`, `deleteMedia`, `listMedia`, `getMedia` on the device (SHELL.md 4.3, L-10). No parity
 * scenario can hold a media (`media.created` is server-only), so the replica is seeded by pulling `media.created` +
 * `deck.created` (the op examples' shape) through the production pull-apply path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class MediaWritesTest {
    private val m1 = "0193a1b2-0007-7000-8000-000000000001"
    private val m2 = "0193a1b2-0007-7000-8000-000000000002"
    private val seg = "0193a1b2-000c-7000-8000-000000000001"
    private val sentence = "0193a1b2-000d-7000-8000-000000000001"
    private val point = "0193a1b2-0008-7000-8000-000000000001"
    private val t0 = 1_795_168_800_000L
    private val me = "01931f3e-0000-7000-8000-0000000000d4"

    private fun created(id: String, title: String, at: Long): List<JsonObject> = listOf(
        RemoteOps.op("media.created", "media", id, """{"id":"$id","title":"$title","kind":"anime","source":"file"}""", at, RemoteOps.SERVER),
        RemoteOps.op(
            "deck.created", "deck", id, """{"id":"$id","name":"$title","type":"media","media_id":"$id","settings":{"order":"deck_frequency"}}""",
            at, RemoteOps.SERVER, counter = 1,
        ),
    )

    private val seed: List<JsonObject> = created(m1, "葬送のフリーレン", t0 - 60_000) + created(m2, "Other", t0 - 30_000)

    private fun d(): ParityDevice = device(me, t0, seed)

    /** The installed snapshot of [m1] (as `ContentInstaller` writes it): one segment, a sentence, an occurrence, a detected span. */
    private fun ParityDevice.install() {
        fun run(sql: String) = driver.execute(null, sql, 0)
        run("UPDATE media SET installed_content_version = 1 WHERE id = '$m1'")
        run("INSERT INTO media_segment (id, media_id, ordinal, kind, title, sentence_count, token_count) VALUES ('$seg', '$m1', 0, 'episode', 'Ep 1', 1, 4)")
        run("INSERT INTO sentence (id, media_id, segment_id, ordinal, text) VALUES ('$sentence', '$m1', '$seg', 0, '食べざるを得ない')")
        run("INSERT INTO occurrence (segment_id, entry_seq, media_id, count) VALUES ('$seg', 1358280, '$m1', 1)")
        run(
            "INSERT INTO sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, media_id, rule_index, created_at) " +
                "VALUES ('$sentence', '$point', 2, 8, 'detected', '$m1', 0, 1)",
        )
    }

    @Test
    fun listAndGetComeFromTheReplicaWithNoSegmentsUntilASnapshotIsInstalled() {
        val d = d()
        val list = d.call("GET", "/api/v1/media").items()
        assertEquals(listOf(m2, m1), list.map { ParityJson.str(it.members["id"]) })
        assertTrue(list.none { "segments" in it.members })
        val got = d.call("GET", "/api/v1/media/$m1").json()
        assertEquals(JsonArray(emptyList()), got.members["segments"])
        assertEquals(JsonString("pending"), got.members["parse_status"])
        assertEquals(JsonNumber(0.0), got.members["content_version"])
        assertEquals(JsonString("葬送のフリーレン"), got.members["title"])
        d.install()
        val segs = ParityJson.arr(d.call("GET", "/api/v1/media/$m1").json().members["segments"])
        assertEquals(listOf(seg), segs.map { ParityJson.str(ParityJson.at(it, "/id")) })
        assertEquals(JsonNumber(1.0), ParityJson.at(segs.single(), "/sentence_count"))
        assertEquals(listOf(m1), d.call("GET", "/api/v1/media", query = "q=%E3%83%95%E3%83%AA%E3%83%BC%E3%83%AC%E3%83%B3").items().map { ParityJson.str(it.members["id"]) })
        assertEquals(emptyList(), d.call("GET", "/api/v1/media", query = "kind=novel").items())
        val page1 = d.call("GET", "/api/v1/media", query = "limit=1").json()
        assertEquals(listOf(m1), d.call("GET", "/api/v1/media", query = "limit=1&cursor=${ParityJson.str(page1.members["next_cursor"])}").items().map { ParityJson.str(it.members["id"]) })
        assertEquals("422 /kind invalid_value", d.call("GET", "/api/v1/media", query = "kind=film").problem())
        assertEquals("404 not_found", d.call("GET", "/api/v1/media/0193a1b2-0007-7000-8000-0000000000ff").problem())
        assertTrue(d.forwarded.isEmpty())
    }

    @Test
    fun renameMintsOneMediaUpdatedAndTheReadsShowItAtOnce() {
        val d = d()
        val hash = "a".repeat(64)
        val r = d.call("PATCH", "/api/v1/media/$m1", """{"title":"Frieren (S1)","kind":"anime","cover_blob_hash":"$hash"}""")
        assertEquals(200, r.status)
        assertEquals(JsonString("Frieren (S1)"), r.json().members["title"])
        assertEquals(JsonString(hash), r.json().members["cover_blob_hash"])
        assertEquals(
            ParityJson.parse("""{"id":"$m1","set":{"title":"Frieren (S1)","kind":"anime","cover_blob_hash":"$hash"}}"""),
            ParityJson.at(d.minted().single(), "/payload"),
        )
        assertEquals(JsonString("Frieren (S1)"), d.call("GET", "/api/v1/media/$m1").json().members["title"])
        assertEquals(200, d.call("PATCH", "/api/v1/media/$m1", """{"cover_blob_hash":null}""").status)
        assertEquals(JsonNull, d.call("GET", "/api/v1/media").items().single { it.members["id"] == JsonString(m1) }.members["cover_blob_hash"])
        // the media deck's name is a separate register (OPS.md media.updated)
        assertEquals(JsonString("葬送のフリーレン"), d.call("GET", "/api/v1/decks/$m1").json().members["name"])
        assertEquals(listOf("media.updated", "media.updated"), d.mintedTypes())
    }

    @Test
    fun deleteTombstonesMediaAndDeckAndRemovesTheInstalledContentButKeepsManualSpans() {
        val d = d()
        d.install()
        assertEquals(201, d.call("POST", "/api/v1/grammar", """{"id":"$point","title":"ざるを得ない"}""").status)
        assertEquals(200, d.call("PUT", "/api/v1/grammar/$point/spans/$sentence/2/8").status)
        assertEquals(204, d.call("DELETE", "/api/v1/media/$m1").status)
        assertEquals(listOf("grammar.created", "grammar.span_put", "media.deleted", "deck.deleted"), d.mintedTypes())
        val ops = d.minted()
        assertEquals(ParityJson.parse("""{"id":"$m1"}"""), ParityJson.at(ops[2], "/payload"))
        assertEquals(ParityJson.parse("""{"id":"$m1"}"""), ParityJson.at(ops[3], "/payload"))
        // tombstones, read-your-writes
        assertEquals("410 gone", d.call("GET", "/api/v1/media/$m1").problem())
        assertEquals(listOf(m2), d.call("GET", "/api/v1/media").items().map { ParityJson.str(it.members["id"]) })
        val deleted = d.call("GET", "/api/v1/media/$m1", query = "include_deleted=true").json()
        assertTrue(deleted.members["deleted_at"] is JsonString)
        assertEquals(JsonArray(emptyList()), deleted.members["segments"])
        assertEquals(410, d.call("GET", "/api/v1/decks/$m1").status)
        // installed content gone, the manual span kept (with its media_id), the detected one gone
        assertEquals(emptyList(), d.rows(LearningTable.MEDIA_SEGMENT))
        assertEquals(emptyList(), d.rows(LearningTable.SENTENCE))
        assertEquals(emptyList(), d.rows(LearningTable.OCCURRENCE))
        val spans = d.rows(LearningTable.SENTENCE_GRAMMAR_SPAN)
        assertEquals(listOf("manual"), spans.map { ParityJson.str(it["origin"]) })
        assertEquals(JsonString(m1), spans.single()["media_id"])
        assertEquals(JsonNumber(0.0), d.rows(LearningTable.MEDIA).single { it["id"] == JsonString(m1) }["installed_content_version"])
        // second delete: 204, nothing minted
        assertEquals(204, d.call("DELETE", "/api/v1/media/$m1").status)
        assertEquals(4, d.mintedTypes().size)
        assertEquals(3, d.localChanges)
    }

    @Test
    fun aMediaWhoseDeckIsAlreadyGoneMintsOnlyTheMediaDelete() {
        val d = d()
        d.pull(listOf(RemoteOps.op("deck.deleted", "deck", m2, """{"id":"$m2"}""", t0 - 1_000, RemoteOps.SERVER)), "deck gone")
        assertEquals(204, d.call("DELETE", "/api/v1/media/$m2").status)
        assertEquals(listOf("media.deleted"), d.mintedTypes())
    }

    @Test
    fun refusalsMintNothing() {
        val d = d()
        val unknown = "0193a1b2-0007-7000-8000-0000000000ff"
        assertEquals("404 not_found", d.call("PATCH", "/api/v1/media/$unknown", """{"title":"x"}""").problem())
        assertEquals("404 not_found", d.call("DELETE", "/api/v1/media/$unknown").problem())
        assertEquals("404 not_found", d.call("DELETE", "/api/v1/media/nope").problem())
        assertEquals("422  required", d.call("PATCH", "/api/v1/media/$m1", "{}").problem())
        assertEquals("422 /title invalid_value", d.call("PATCH", "/api/v1/media/$m1", """{"title":""}""").problem())
        assertEquals("422 /kind invalid_value", d.call("PATCH", "/api/v1/media/$m1", """{"kind":"film"}""").problem())
        assertEquals("422 /cover_blob_hash invalid_value", d.call("PATCH", "/api/v1/media/$m1", """{"cover_blob_hash":"ABC"}""").problem())
        assertEquals("422 /source unknown_field", d.call("PATCH", "/api/v1/media/$m1", """{"source":"url"}""").problem())
        assertEquals(204, d.call("DELETE", "/api/v1/media/$m1").status)
        assertEquals("410 gone", d.call("PATCH", "/api/v1/media/$m1", """{"title":"x"}""").problem())
        assertEquals(listOf("media.deleted", "deck.deleted"), d.mintedTypes())
    }

    @Test
    fun mintedMediaOpsValidateAndConvergeInAnyOrder() {
        val d = d()
        assertEquals(200, d.call("PATCH", "/api/v1/media/$m1", """{"title":"A"}""").status)
        assertEquals(200, d.call("PATCH", "/api/v1/media/$m1", """{"kind":"novel","title":"B"}""", at = t0 + 1_000).status)
        assertEquals(204, d.call("DELETE", "/api/v1/media/$m1", at = t0 + 2_000).status)
        assertEquals(200, d.call("PATCH", "/api/v1/media/$m2", """{"title":"C"}""", at = t0 + 3_000).status)
        assertOpsValidAndConverge(d, seed, listOf(LearningTable.MEDIA, LearningTable.DECK), t0 + 60_000, emptySet())
    }
}
