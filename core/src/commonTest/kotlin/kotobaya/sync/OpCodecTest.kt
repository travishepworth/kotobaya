package kotobaya.sync

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.EnvelopeError
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `OpCodec` against every example of `contracts/sync/examples` (embedded in [SyncExamples]). */
class OpCodecTest {
    private val c = DefaultOpCodec
    private val j = DefaultCanonicalJson

    private fun tree(text: String): JsonObject = j.decode(text).value as JsonObject

    private fun sourceOf(t: JsonObject) = if ("seq" in t.members) OpSource.PULLED else OpSource.PUSHED

    private fun known(text: String, source: OpSource): Op {
        val r = c.decode(text, source)
        assertIs<OpDecodeResult.Known>(r, "expected Known, got $r")
        return r.op
    }

    private fun with(t: JsonObject, pointer: List<String>, value: JsonValue?): JsonObject {
        val m = LinkedHashMap(t.members)
        if (pointer.size == 1) {
            if (value == null) m.remove(pointer[0]) else m[pointer[0]] = value
        } else {
            m[pointer[0]] = with(m.getValue(pointer[0]) as JsonObject, pointer.drop(1), value)
        }
        return JsonObject(m)
    }

    @Test
    fun everyOpTypeHasAnExample() {
        val types = SyncExamples.ops.values.map { (tree(it).members.getValue("op_type") as JsonString).value }.toSet()
        assertEquals(OpType.entries.map { it.wire }.toSet(), types)
    }

    @Test
    fun everyExampleOpDecodesKnownAndReencodesCanonically() {
        assertTrue(SyncExamples.ops.size >= 45)
        for ((name, text) in SyncExamples.ops) {
            val t = tree(text)
            val src = sourceOf(t)
            val op = known(text, src)
            assertEquals(emptyList(), c.envelopeErrors(op, src), name)
            val canonical = c.encode(op)
            assertEquals(j.encode(t), canonical, name)
            assertEquals(t, c.encodeTree(op), name)
            assertEquals(OpDecodeResult.Known(op), c.decode(canonical, src), name)
            assertEquals(OpDecodeResult.Known(op), c.decodeTree(t, src), name)
            assertEquals(j.utf8Size(canonical), c.sizeBytes(op), name)
            assertEquals(c.sizeBytes(op), c.rawSizeBytes(t), name)
            assertEquals(op.opType.entityType, op.entityType, name)
            assertEquals(c.entityIdOf(op.opType, op.payload), op.entityId, name)
        }
    }

    /** Goldens computed with JavaScript (`JSON.stringify` of the key-sorted value) and `Buffer.byteLength`. */
    @Test
    fun canonicalGoldensFromJavaScript() {
        val goldens = mapOf(
            "note.created.json" to (648 to """{"device_id":"01931f3e-0000-7000-8000-0000000000a1","entity_id":"0193a1b2-0001-7000-8000-000000000001","entity_type":"note","hlc":"0193a1b2c008-0000-01931f3e-0000-7000-8000-0000000000a1","op_id":"0193a1b2-00ff-7000-8000-000000000008","op_type":"note.created","op_version":1,"payload":{"entry_seq":1358280,"fields":{"sentence":"パンを食べる"},"id":"0193a1b2-0001-7000-8000-000000000001","note_type_id":"00000000-0000-7000-8000-000000000101","origin":"reader","source_media_id":"0193a1b2-0007-7000-8000-000000000001","source_sentence":"パンを食べる","source_sentence_id":"0193a1b2-0009-7000-8000-000000000001","tags":["anime","frieren"]}}"""),
            "fsrs_params.published.json" to (678 to """{"caused_by_op_id":null,"device_id":"00000000-0000-7000-8000-000000000001","entity_id":"0193a1b2-0006-7000-8000-000000000001","entity_type":"fsrs_params","hlc":"0193a1b2c007-0000-00000000-0000-7000-8000-000000000001","op_id":"0193a1b2-00ff-7000-8000-000000000007","op_type":"fsrs_params.published","op_version":1,"payload":{"fsrs_version":"6","id":"0193a1b2-0006-7000-8000-000000000001","optimized_at":1733584666631,"optimizer_stats":{"log_loss":0.3412,"review_count":5321,"rmse_bins":0.0312},"weights":[0.212,1.2931,2.3065,8.2956,6.4133,0.8334,3.0194,0.001,1.8722,0.1666,0.796,1.4835,0.0614,0.2629,1.6483,0.6014,1.8729,0.5425,0.0912,0.0658,0.1542],"weights_version":1},"seq":7}"""),
            "deck.created.json" to (433 to """{"device_id":"01931f3e-0000-7000-8000-0000000000a1","entity_id":"0193a1b2-0004-7000-8000-000000000001","entity_type":"deck","hlc":"0193a1b2c017-0000-01931f3e-0000-7000-8000-0000000000a1","op_id":"0193a1b2-00ff-7000-8000-000000000017","op_type":"deck.created","op_version":1,"payload":{"id":"0193a1b2-0004-7000-8000-000000000001","name":"Mined words","new_cards_per_day":5,"priority":10,"settings":{"order":"position"},"type":"user"}}"""),
            "review.logged.json" to (559 to """{"device_id":"01931f3e-0000-7000-8000-0000000000a1","entity_id":"0193a1b2-0003-7000-8000-000000000001","entity_type":"review_log","hlc":"0193a1b2c011-0000-01931f3e-0000-7000-8000-0000000000a1","op_id":"0193a1b2-00ff-7000-8000-000000000011","op_type":"review.logged","op_version":1,"payload":{"button":"good","card_id":"0193a1b2-0002-7000-8000-000000000001","duration_ms":4200,"id":"0193a1b2-0003-7000-8000-000000000001","rating":3,"review_type":"learning","reviewed_at":1733584666641,"sentence_id":"0193a1b2-0009-7000-8000-000000000001","utc_offset_min":540}}"""),
        )
        for ((name, g) in goldens) {
            val text = SyncExamples.ops.getValue(name)
            val op = known(text, sourceOf(tree(text)))
            assertEquals(g.second, c.encode(op), name)
            assertEquals(g.first, c.sizeBytes(op), name)
        }
    }

    private sealed interface Expect {
        data class Invalid(val pointer: String) : Expect
        data class Unsupported(val opType: String) : Expect
        /** A whole HTTP body: fails [SyncSchemas.validate]; if [opSource] is set, its ops decode `Invalid` at [pointer]. */
        data class Body(val opSource: OpSource? = null, val pointer: String = "") : Expect
    }

    /** One entry per file of `examples/invalid`, with the rule its `why` names. */
    private val invalidExpectations: Map<String, Expect> = mapOf(
        "deck-created-smart-without-query.json" to Expect.Invalid("/payload"),
        "deck-created-user-with-query.json" to Expect.Invalid("/payload/query"),
        "deck-item-bad-key.json" to Expect.Invalid("/payload/item_key"),
        "fsrs-params-published-weight-out-of-range.json" to Expect.Invalid("/payload/weights/7"),
        "fsrs-params-published-wrong-count.json" to Expect.Invalid("/payload/weights"),
        "fsrs-params-published-zero-decay.json" to Expect.Invalid("/payload/weights/20"),
        "note-updated-empty-set.json" to Expect.Invalid("/payload/set"),
        "note-updated-partial-source.json" to Expect.Invalid("/payload/set"),
        "op-payload-unknown-property.json" to Expect.Invalid("/payload/note"),
        "op-short-hlc.json" to Expect.Invalid("/hlc"),
        "op-unknown-type.json" to Expect.Unsupported("review.deleted"),
        "op-uppercase-uuid.json" to Expect.Invalid("/op_id"),
        "op-wrong-entity-type.json" to Expect.Invalid("/entity_type"),
        "pull-op-without-seq.json" to Expect.Body(OpSource.PULLED, ""),
        "pull-response-without-log-epoch.json" to Expect.Body(),
        "push-request-empty.json" to Expect.Body(),
        "push-request-with-seq.json" to Expect.Body(OpSource.PUSHED, "/seq"),
        "push-result-accepted-without-seq.json" to Expect.Body(),
        "push-result-rejected-without-error.json" to Expect.Body(),
        "review-button-rating-mismatch.json" to Expect.Invalid("/payload/rating"),
        "review-manual-with-rating.json" to Expect.Invalid("/payload"), // required manual_action is checked before then.rating
        "review-set-due-without-date.json" to Expect.Invalid("/payload/manual_due_at"),
    )

    @Test
    fun everyInvalidExampleIsRejectedWithTheExpectedReason() {
        assertEquals(invalidExpectations.keys, SyncExamples.invalid.keys, "examples/invalid changed: update the expectations")
        for ((name, text) in SyncExamples.invalid) {
            val file = tree(text)
            val schema = (file.members.getValue("schema") as JsonString).value
            val instance = file.members.getValue("instance")
            // every instance fails the schema the file names, with the generic validator
            assertNotNull(SyncSchemas.validate(schema, instance), "$name must fail $schema")
            when (val e = invalidExpectations.getValue(name)) {
                is Expect.Invalid -> {
                    val r = c.decodeTree(instance, OpSource.PUSHED)
                    assertIs<OpDecodeResult.Invalid>(r, name)
                    assertEquals(e.pointer, r.pointer, "$name: ${r.message}")
                    assertEquals(e.pointer, SyncSchemas.validate(schema, instance)!!.pointer, name)
                }
                is Expect.Unsupported -> {
                    val r = c.decodeTree(instance, OpSource.PUSHED)
                    assertIs<OpDecodeResult.Unsupported>(r, name)
                    assertEquals(e.opType, r.opType)
                    assertEquals(1, r.opVersion)
                    assertNull(r.seq)
                }
                is Expect.Body -> if (e.opSource != null) {
                    val ops = ((instance as JsonObject).members.getValue("ops") as JsonArray).items
                    for (op in ops) {
                        val r = c.decodeTree(op, e.opSource)
                        assertIs<OpDecodeResult.Invalid>(r, name)
                        assertEquals(e.pointer, r.pointer, "$name: ${r.message}")
                    }
                }
            }
        }
    }

    @Test
    fun batchExamplesValidate() {
        val shapes = mapOf(
            "push-request.json" to "batch.schema.json#/\$defs/PushRequest",
            "push-response.json" to "batch.schema.json#/\$defs/PushResponse",
            "push-response.rejected.json" to "batch.schema.json#/\$defs/PushResponse",
            "push-response.retry.json" to "batch.schema.json#/\$defs/PushResponse",
            "pull-response.json" to "batch.schema.json#/\$defs/PullResponse",
            "pull-response.empty.json" to "batch.schema.json#/\$defs/PullResponse",
        )
        assertEquals(shapes.keys, SyncExamples.batches.keys)
        for ((name, ref) in shapes) {
            val v = j.decode(SyncExamples.batches.getValue(name)).value!!
            assertNull(SyncSchemas.validate(ref, v), name)
            val lenient = ref.replace("PushRequest", "PushRequestLenient").replace("PullResponse", "PullResponseLenient")
            assertNull(SyncSchemas.validate(lenient, v), "$name lenient")
        }
    }

    @Test
    fun unknownTypeOrNewerVersionIsUnsupportedWithSeq() {
        val base = tree(SyncExamples.ops.getValue("note.created.json"))
        val pulled = with(with(base, listOf("seq"), JsonNumber(42.0)), listOf("caused_by_op_id"), kotobaya.contracts.sync.JsonNull)
        val foo = with(with(pulled, listOf("op_type"), JsonString("foo.bar")), listOf("payload"), JsonObject(mapOf("anything" to JsonBool(true))))
        val r1 = c.decodeTree(foo, OpSource.PULLED)
        assertIs<OpDecodeResult.Unsupported>(r1)
        assertEquals("foo.bar", r1.opType)
        assertEquals(1, r1.opVersion)
        assertEquals(42L, r1.seq)
        assertEquals(foo, r1.raw)

        val v2 = with(with(pulled, listOf("op_version"), JsonNumber(2.0)), listOf("payload"), JsonObject(emptyMap()))
        val r2 = c.decodeTree(v2, OpSource.PULLED)
        assertIs<OpDecodeResult.Unsupported>(r2)
        assertEquals("note.created", r2.opType)
        assertEquals(2, r2.opVersion)
        assertEquals(42L, r2.seq)

        val r3 = c.decodeTree(with(base, listOf("op_type"), JsonString("foo.bar")), OpSource.PUSHED)
        assertIs<OpDecodeResult.Unsupported>(r3)
        assertNull(r3.seq)

        // the lenient envelope shape still applies
        for ((ptr, v) in listOf(
            "op_type" to JsonString("Foo.bar"),
            "op_type" to JsonString("foo"),
            "op_version" to JsonNumber(0.0),
            "op_version" to JsonNumber(1.5),
            "op_version" to JsonString("1"),
            "hlc" to JsonString("x"),
            "payload" to JsonArray(emptyList()),
            "entity_id" to JsonNumber(1.0),
        )) {
            val r = c.decodeTree(with(foo, listOf(ptr), v), OpSource.PULLED)
            assertIs<OpDecodeResult.Invalid>(r, "$ptr = $v")
            assertEquals("/$ptr", r.pointer)
            assertEquals(42L, r.seq)
        }
        // a pulled op must carry seq and caused_by_op_id even when unsupported
        val noSeq = c.decodeTree(with(foo, listOf("seq"), null), OpSource.PULLED)
        assertIs<OpDecodeResult.Invalid>(noSeq)
        assertNull(noSeq.seq)
    }

    @Test
    fun syntaxErrorsAndNonObjectsAreInvalid() {
        for (t in listOf("", "{", "[]", "1", "null", "{\"op_id\":1,\"op_id\":1}")) {
            val r = c.decode(t, OpSource.PUSHED)
            assertIs<OpDecodeResult.Invalid>(r, t)
            assertEquals("", r.pointer)
            assertNull(r.seq)
        }
    }

    @Test
    fun seqPresenceFollowsSource() {
        val base = tree(SyncExamples.ops.getValue("review.logged.json"))
        val withSeq = with(base, listOf("seq"), JsonNumber(3.0))
        assertEquals("/seq", (c.decodeTree(withSeq, OpSource.PUSHED) as OpDecodeResult.Invalid).pointer)
        val withCause = with(base, listOf("caused_by_op_id"), kotobaya.contracts.sync.JsonNull)
        assertEquals("/caused_by_op_id", (c.decodeTree(withCause, OpSource.PUSHED) as OpDecodeResult.Invalid).pointer)
        assertIs<OpDecodeResult.Invalid>(c.decodeTree(base, OpSource.PULLED))
        assertIs<OpDecodeResult.Invalid>(c.decodeTree(withSeq, OpSource.PULLED)) // caused_by_op_id missing
        val pulled = with(withSeq, listOf("caused_by_op_id"), JsonString("0193a1b2-00ff-7000-8000-000000000001"))
        val op = (c.decodeTree(pulled, OpSource.PULLED) as OpDecodeResult.Known).op
        assertEquals(3L, op.seq)
        assertEquals("0193a1b2-00ff-7000-8000-000000000001", op.causedByOpId)
        assertEquals(listOf(EnvelopeError.SEQ_PRESENCE), c.envelopeErrors(op, OpSource.PUSHED))
        assertEquals(emptyList(), c.envelopeErrors(op, OpSource.PULLED))
        assertEquals(listOf(EnvelopeError.SEQ_PRESENCE), c.envelopeErrors(op.copy(seq = null, causedByOpId = null), OpSource.PULLED))
        assertEquals(listOf(EnvelopeError.SEQ_PRESENCE), c.envelopeErrors(op.copy(seq = null), OpSource.PUSHED))
        assertEquals(pulled, c.encodeTree(op))
        // seq 0 or fractional is invalid
        assertIs<OpDecodeResult.Invalid>(c.decodeTree(with(pulled, listOf("seq"), JsonNumber(0.0)), OpSource.PULLED))
        assertIs<OpDecodeResult.Invalid>(c.decodeTree(with(pulled, listOf("seq"), JsonNumber(1.5)), OpSource.PULLED))
    }

    @Test
    fun envelopeConsistencyRules() {
        val text = SyncExamples.ops.getValue("review.logged.json")
        val op = known(text, OpSource.PUSHED)
        assertEquals(listOf(EnvelopeError.ENTITY_ID_MISMATCH), c.envelopeErrors(op.copy(entityId = "x"), OpSource.PUSHED))
        assertEquals(
            listOf(EnvelopeError.DEVICE_ID_MISMATCH),
            c.envelopeErrors(op.copy(deviceId = "01931f3e-0000-7000-8000-0000000000b2"), OpSource.PUSHED),
        )
        assertEquals(listOf(EnvelopeError.ENTITY_TYPE_MISMATCH), c.envelopeErrors(op.copy(entityType = "card"), OpSource.PUSHED))
        // an entity_id that does not match decodes Known (JSON Schema cannot see it) and is caught here
        val bad = with(tree(text), listOf("entity_id"), JsonString("0193a1b2-0003-7000-8000-000000000999"))
        val badOp = (c.decodeTree(bad, OpSource.PUSHED) as OpDecodeResult.Known).op
        assertEquals(listOf(EnvelopeError.ENTITY_ID_MISMATCH), c.envelopeErrors(badOp, OpSource.PUSHED))
        // payload without the id part
        val noId = op.copy(payload = JsonObject(op.payload.members - "id"))
        assertEquals(listOf(EnvelopeError.ENTITY_ID_MISMATCH), c.envelopeErrors(noId, OpSource.PUSHED))
    }

    @Test
    fun entityIdDerivation() {
        val span = JsonObject(
            mapOf(
                "sentence_id" to JsonString("s"),
                "grammar_point_id" to JsonString("g"),
                "start_offset" to JsonNumber(3.0),
                "end_offset" to JsonNumber(5.0),
            ),
        )
        assertEquals("s|g|3|5|manual", c.entityIdOf(OpType.GRAMMAR_SPAN_PUT, span))
        assertEquals("1358280", c.entityIdOf(OpType.WORD_STATUS_SET, JsonObject(mapOf("entry_seq" to JsonNumber(1358280.0)))))
        assertEquals(
            "d|e:1",
            c.entityIdOf(OpType.DECK_ITEM_PUT, JsonObject(mapOf("deck_id" to JsonString("d"), "item_key" to JsonString("e:1")))),
        )
        assertFailsWith<IllegalArgumentException> { c.entityIdOf(OpType.NOTE_CREATED, JsonObject(emptyMap())) }
        assertFailsWith<IllegalArgumentException> { c.entityIdOf(OpType.NOTE_CREATED, JsonObject(mapOf("id" to JsonBool(true)))) }
        assertFailsWith<IllegalArgumentException> {
            c.entityIdOf(OpType.WORD_STATUS_SET, JsonObject(mapOf("entry_seq" to JsonNumber(1.5))))
        }
    }

    @Test
    fun integerTypedPropertiesNeedIntegralValuesBelow2pow53() {
        val base = tree(SyncExamples.ops.getValue("note.created.json"))
        val ok = with(base, listOf("payload", "entry_seq"), JsonNumber(9007199254740991.0))
        assertIs<OpDecodeResult.Known>(c.decodeTree(ok, OpSource.PUSHED))
        val big = with(base, listOf("payload", "entry_seq"), JsonNumber(9007199254740992.0))
        assertEquals("/payload/entry_seq", (c.decodeTree(big, OpSource.PUSHED) as OpDecodeResult.Invalid).pointer)
        val frac = with(base, listOf("payload", "entry_seq"), JsonNumber(1.5))
        assertEquals("/payload/entry_seq", (c.decodeTree(frac, OpSource.PUSHED) as OpDecodeResult.Invalid).pointer)
        // 3.0 is the integer 3 and re-encodes as 3
        val text = SyncExamples.ops.getValue("review.logged.json").replace("\"rating\": 3", "\"rating\": 3.0")
        assertTrue(text.contains("3.0"))
        val op = known(text, OpSource.PUSHED)
        assertTrue(c.encode(op).contains("\"rating\":3,"))
        // op_version 1.0 is version 1
        assertIs<OpDecodeResult.Known>(c.decodeTree(with(base, listOf("op_version"), JsonNumber(1.0)), OpSource.PUSHED))
        // a number-typed property may be any finite number, including >= 2^53
        val fsrs = tree(SyncExamples.ops.getValue("fsrs_params.published.json"))
        val huge = with(fsrs, listOf("payload", "optimizer_stats", "log_loss"), JsonNumber(1e20))
        val hop = (c.decodeTree(huge, OpSource.PULLED) as OpDecodeResult.Known).op
        assertTrue(c.encode(hop).contains("\"log_loss\":100000000000000000000"))
        // an integer-typed property >= 2^53 inside an open nested object is still checked
        val bigCount = with(fsrs, listOf("payload", "optimizer_stats", "review_count"), JsonNumber(1e20))
        assertEquals(
            "/payload/optimizer_stats/review_count",
            (c.decodeTree(bigCount, OpSource.PULLED) as OpDecodeResult.Invalid).pointer,
        )
    }

    @Test
    fun rawSizeIgnoresWhitespaceAndMemberOrderAndCoversInvalidOps() {
        val pretty = SyncExamples.ops.getValue("note.created.json")
        val raw = j.decode(pretty).value!!
        val op = known(pretty, OpSource.PUSHED)
        assertEquals(c.sizeBytes(op), c.rawSizeBytes(raw))
        assertTrue(j.utf8Size(pretty) > c.sizeBytes(op))
        // reversed member order at every depth, extra whitespace
        val reversed = "{" + (raw as JsonObject).members.entries.reversed().joinToString(" ,\n ") { (k, v) ->
            "\"$k\" :  " + if (v is JsonObject) {
                "{ " + v.members.entries.reversed().joinToString(" , ") { (k2, v2) -> "\"$k2\":${j.encode(v2)}" } + " }"
            } else {
                j.encode(v)
            }
        } + "}"
        val raw2 = j.decode(reversed).value!!
        assertEquals(c.sizeBytes(op), c.rawSizeBytes(raw2))
        // unsupported and invalid ops are measured the same way
        val unknown = with(raw, listOf("op_type"), JsonString("foo.bar"))
        assertIs<OpDecodeResult.Unsupported>(c.decodeTree(unknown, OpSource.PUSHED))
        assertEquals(j.sizeOf(unknown), c.rawSizeBytes(unknown))
        val invalid = with(raw, listOf("payload", "zzz"), JsonNumber(1.0))
        assertIs<OpDecodeResult.Invalid>(c.decodeTree(invalid, OpSource.PUSHED))
        assertEquals(c.sizeBytes(op) + ",\"zzz\":1".length, c.rawSizeBytes(invalid))
        assertEquals(5, c.rawSizeBytes(JsonString("食")))
    }

    @Test
    fun equalOpsWithDifferentPayloadOrderEncodeIdentically() {
        val op = known(SyncExamples.ops.getValue("note.created.json"), OpSource.PUSHED)
        val reordered = op.copy(payload = JsonObject(LinkedHashMap(op.payload.members.entries.reversed().associate { it.key to it.value })))
        assertEquals(op, reordered)
        assertEquals(c.encode(op), c.encode(reordered))
        assertEquals(c.sizeBytes(op), c.sizeBytes(reordered))
    }

    @Test
    fun maxOpBytesBoundary() {
        val op = known(SyncExamples.ops.getValue("note.updated.json"), OpSource.PUSHED)
        val set = op.payload.members.getValue("set") as JsonObject
        fun withMnemonic(s: String) = op.copy(
            payload = JsonObject(op.payload.members + ("set" to JsonObject(set.members + ("mnemonic" to JsonString(s))))),
        )
        val max = Reserved.DEFAULT_MAX_OP_BYTES
        val overhead = c.sizeBytes(withMnemonic(""))
        // fill with 3-byte characters and ASCII so the op is exactly max bytes
        val budget = max - overhead
        val text = "食".repeat(budget / 3) + "a".repeat(budget % 3)
        val exact = withMnemonic(text)
        assertEquals(max, c.sizeBytes(exact))
        assertTrue(c.sizeBytes(exact) <= max) // allowed
        val over = withMnemonic(text + "a")
        assertEquals(max + 1, c.sizeBytes(over)) // refused
        assertIs<OpDecodeResult.Known>(c.decode(c.encode(exact), OpSource.PUSHED))
    }
}
