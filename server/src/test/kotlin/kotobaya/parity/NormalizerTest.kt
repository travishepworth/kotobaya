package kotobaya.parity

import kotobaya.server.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SHELL.md 8.3 on hand-made answers. */
class NormalizerTest {
    private val known = "0193a1b2-0002-7000-8000-000000000010"
    private val dev = ParityServerHarness.SERVER_DEVICE
    private val minted1 = "019a0000-0000-7000-8000-0000000000aa"
    private val minted2 = "019a0000-0000-7000-8000-0000000000bb"

    private val seedDevice = "01931f3e-0000-7000-8000-0000000000a1"

    private fun n() = Normalizer(setOf(known, seedDevice), dev)

    @Test
    fun headersKeepOnlyThreeAndMediaTypeLowerCased() {
        val out = n().step(
            RawResponse(
                201,
                mapOf("content-type" to "Application/JSON; charset=UTF-8", "x-request-id" to "r", "kotobaya-seq" to "9", "retry-after" to "2", "content-location" to "/api/v1/notes/$minted1"),
                "{}",
            ),
        )
        assertEquals(json.readTree("""{"content-type":"application/json","retry-after":"2","content-location":"/api/v1/notes/<id1>"}"""), out["headers"])
    }

    @Test
    fun mintedIdsAndHlcsNumberedByFirstAppearanceInKeyOrderAcrossSteps() {
        val hlc = "01a1f82f1e80-0000-$dev"
        val seedHlc = "01a1f82f1e80-0000-01931f3e-0000-7000-8000-0000000000a1"
        val norm = n()
        val a = norm.step(RawResponse(200, mapOf("content-type" to "application/json"),
            """{"z":"$minted1","a":"n:$minted2","k":"$known","b":"00000000-0000-7000-8000-000000000101","d":"$dev","h":"$hlc","s":"$seedHlc","generated_at":"x","nested":{"generated_at":1}}"""))
        assertEquals(
            json.readTree("""{"z":"<id2>","a":"n:<id1>","k":"$known","b":"00000000-0000-7000-8000-000000000101","d":"<device>","h":"<hlc1>","s":"$seedHlc","nested":{}}"""),
            a["body"],
        )
        val b = norm.step(RawResponse(200, emptyMap(), """["$minted1","$hlc"]"""))
        assertEquals(json.readTree("""["<id2>","<hlc1>"]"""), b["body"])
    }

    @Test
    fun problemBodiesDropVolatileMembersAndSortErrors() {
        val out = n().step(RawResponse(422, mapOf("content-type" to "application/problem+json"),
            """{"type":"t","title":"T","status":422,"code":"validation_failed","request_id":"r","detail":"d",
               "errors":[{"pointer":"/rating","code":"b","detail":"x"},{"pointer":"/button","code":"z"},{"pointer":"/button","code":"a"}]}"""))
        assertEquals(
            json.readTree("""{"type":"t","status":422,"code":"validation_failed","errors":[{"pointer":"/button","code":"a"},{"pointer":"/button","code":"z"},{"pointer":"/rating","code":"b"}]}"""),
            out["body"],
        )
    }

    @Test
    fun ignoreWithWildcardsAndMissingTargetsAndEmptyOrTextBodies() {
        val out = n().step(RawResponse(200, emptyMap(), """{"items":[{"card":{"updated_at":1,"id":"x"}},{"card":{"id":"y"}}]}"""),
            listOf("/items/*/card/updated_at", "/nope/deeper"))
        assertEquals(json.readTree("""{"items":[{"card":{"id":"x"}},{"card":{"id":"y"}}]}"""), out["body"])
        assertTrue(n().step(RawResponse(204, emptyMap(), null))["body"].isNull)
        assertEquals("not json", n().step(RawResponse(500, emptyMap(), "not json"))["body"].asText())
    }

    @Test
    fun jsonEqualComparesNumbersAsDoublesAndIgnoresMemberOrder() {
        assertTrue(Normalizer.jsonEqual(json.readTree("""{"a":1,"b":[2.0]}"""), json.readTree("""{"b":[2],"a":1.0}""")))
        assertTrue(!Normalizer.jsonEqual(json.readTree("""{"a":1}"""), json.readTree("""{"a":"1"}""")))
    }
}
