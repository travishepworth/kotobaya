package kotobaya.android.shell.parity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonString

/** SHELL.md 8.3 on hand-made answers; the same cases as the server harness's NormalizerTest (SHELL-P1). */
class NormalizerTest {
    private val known = "0193a1b2-0002-7000-8000-000000000010"
    private val dev = "01931f3e-0000-7000-8000-0000000000d4"
    private val minted1 = "019a0000-0000-7000-8000-0000000000aa"
    private val minted2 = "019a0000-0000-7000-8000-0000000000bb"
    private val seedDevice = "01931f3e-0000-7000-8000-0000000000a1"

    private fun n() = Normalizer(setOf(known, seedDevice, dev), dev)

    private fun j(s: String) = ParityJson.parse(s)

    @Test
    fun headersKeepOnlyThreeAndMediaTypeLowerCased() {
        val out = n().step(
            RawResponse(
                201,
                mapOf(
                    "content-type" to "Application/JSON; charset=UTF-8", "x-request-id" to "r", "kotobaya-seq" to "9",
                    "kotobaya-local" to "1", "retry-after" to "2", "content-location" to "/api/v1/notes/$minted1",
                ),
                "{}",
            ),
        )
        assertEquals(j("""{"content-type":"application/json","retry-after":"2","content-location":"/api/v1/notes/<id1>"}"""), out.members["headers"])
    }

    @Test
    fun mintedIdsAndHlcsNumberedByFirstAppearanceInKeyOrderAcrossSteps() {
        val hlc = "01a1f82f1e80-0000-$dev"
        val seedHlc = "01a1f82f1e80-0000-$seedDevice"
        val norm = n()
        val a = norm.step(
            RawResponse(
                200, mapOf("content-type" to "application/json"),
                """{"z":"$minted1","a":"n:$minted2","k":"$known","b":"00000000-0000-7000-8000-000000000101","d":"$dev","h":"$hlc","s":"$seedHlc","generated_at":"x","nested":{"generated_at":1}}""",
            ),
        )
        assertEquals(
            j("""{"z":"<id2>","a":"n:<id1>","k":"$known","b":"00000000-0000-7000-8000-000000000101","d":"<device>","h":"<hlc1>","s":"$seedHlc","nested":{}}"""),
            a.members["body"],
        )
        val b = norm.step(RawResponse(200, emptyMap(), """["$minted1","$hlc"]"""))
        assertEquals(j("""["<id2>","<hlc1>"]"""), b.members["body"])
    }

    @Test
    fun problemBodiesDropVolatileMembersAndSortErrors() {
        val out = n().step(
            RawResponse(
                422, mapOf("content-type" to "application/problem+json"),
                """{"type":"t","title":"T","status":422,"code":"validation_failed","request_id":"r","detail":"d",
                   "errors":[{"pointer":"/rating","code":"b","detail":"x"},{"pointer":"/button","code":"z"},{"pointer":"/button","code":"a"}]}""",
            ),
        )
        assertEquals(
            j("""{"type":"t","status":422,"code":"validation_failed","errors":[{"pointer":"/button","code":"a"},{"pointer":"/button","code":"z"},{"pointer":"/rating","code":"b"}]}"""),
            out.members["body"],
        )
    }

    @Test
    fun ignoreWithWildcardsAndMissingTargetsAndEmptyOrTextBodies() {
        val out = n().step(
            RawResponse(200, emptyMap(), """{"items":[{"card":{"updated_at":1,"id":"x"}},{"card":{"id":"y"}}]}"""),
            listOf("/items/*/card/updated_at", "/nope/deeper"),
        )
        assertEquals(j("""{"items":[{"card":{"id":"x"}},{"card":{"id":"y"}}]}"""), out.members["body"])
        assertEquals(JsonNull, n().step(RawResponse(204, emptyMap(), null)).members["body"])
        assertEquals(JsonString("not json"), n().step(RawResponse(500, emptyMap(), "not json")).members["body"])
    }

    @Test
    fun jsonEqualComparesNumbersAsDoublesAndIgnoresMemberOrder() {
        assertEquals(true, ParityJson.equal(j("""{"a":1,"b":[2.0]}"""), j("""{"b":[2],"a":1.0}""")))
        assertEquals(false, ParityJson.equal(j("""{"a":1}"""), j("""{"a":"1"}""")))
    }
}
