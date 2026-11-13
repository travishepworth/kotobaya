package kotobaya.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType

/**
 * BUG-8: the faster validator (verdict-only sub-evaluation, cached `$ref`s) gives exactly the result of
 * the old one ([LegacySyncSchemas]): the same verdict, message and pointer, for every example and
 * invalid example of `contracts/sync/examples` and for thousands of seeded mutations of the example
 * ops, against every schema the sync code validates with.
 */
class SchemaEquivalenceTest {
    private fun v(text: String): JsonValue = checkNotNull(DefaultCanonicalJson.decode(text).value)

    private val pulledItem = "batch.schema.json#/\$defs/PullResponseLenient/properties/ops/items"

    /** DefaultOpCodec's pushed lenient shape, rebuilt the same way. */
    private val pushedLenient: JsonObject by lazy {
        val pulled = SyncSchemas.resolveRef(pulledItem) as JsonObject
        val m = LinkedHashMap(pulled.members)
        m["required"] = JsonArray(
            (m.getValue("required") as JsonArray).items.filter { it != JsonString("seq") && it != JsonString("caused_by_op_id") },
        )
        JsonObject(m)
    }

    private val opRefs = listOf("op.schema.json", pulledItem)
    private val batchRefs = listOf(
        "batch.schema.json#/\$defs/PullResponseLenient", "batch.schema.json#/\$defs/PullResponse",
        "batch.schema.json#/\$defs/PushRequestLenient", "batch.schema.json#/\$defs/PushResponse",
    )

    private var compared = 0
    private var rejected = 0

    private fun same(ref: String, instance: JsonValue, context: String) {
        val old = LegacySyncSchemas.validate(ref, instance)
        val new = SyncSchemas.validate(ref, instance)
        assertEquals(old, new, "$context against $ref: ${DefaultCanonicalJson.encode(instance).take(300)}")
        compared++
        if (old != null) rejected++
    }

    private fun sameOp(op: JsonValue, context: String) {
        for (ref in opRefs) same(ref, op, context)
        val old = LegacySyncSchemas.validateWith(pushedLenient, "batch.schema.json", op)
        assertEquals(old, SyncSchemas.validateWith(pushedLenient, "batch.schema.json", op), "$context against the pushed lenient shape")
        compared++
        val m = (op as? JsonObject)?.members ?: return
        val type = (m["op_type"] as? JsonString)?.value ?: return
        val payload = m["payload"] ?: return
        val file = "ops/$type.schema.json"
        if (file in SyncSchemas.files) same(file, payload, "$context payload")
    }

    @Test
    fun everyExampleAndInvalidExampleGetsTheSameResult() {
        for ((name, text) in SyncExamples.ops) sameOp(v(text), name)
        for ((name, text) in SyncExamples.invalid + SyncExamples.invalidCodec) {
            val doc = v(text) as JsonObject
            val schema = (doc.members.getValue("schema") as JsonString).value
            val instance = doc.members.getValue("instance")
            same(schema, instance, name)
            sameOp(instance, name)
        }
        for ((name, text) in SyncExamples.batches) {
            val doc = v(text)
            for (ref in batchRefs) same(ref, doc, name)
            ((doc as JsonObject).members["ops"] as? JsonArray)?.items?.forEach { sameOp(it, name) }
        }
        assertTrue(rejected > 0 && rejected < compared, "the corpus has both valid and invalid instances ($rejected / $compared)")
    }

    @Test
    fun seededMutationsOfTheExampleOpsGetTheSameResult() {
        val r = Random(20261113)
        val bases = SyncExamples.ops.values.map(::v)
        repeat(MUTANTS) { i ->
            var op = bases[r.nextInt(bases.size)]
            repeat(1 + r.nextInt(3)) { op = mutate(op, r) }
            sameOp(op, "mutant $i")
        }
        // the fuzz must exercise both outcomes, or it proves nothing
        assertTrue(rejected > MUTANTS / 4, "rejected $rejected of $compared")
        assertTrue(compared - rejected > MUTANTS / 10, "accepted ${compared - rejected} of $compared")
    }

    // ---------------------------------------------------------------------------------- mutations

    private val types = OpType.entries.map { it.wire }

    private fun randomValue(r: Random, depth: Int = 0): JsonValue = when (r.nextInt(if (depth > 2) 9 else 12)) {
        0 -> JsonNull
        1 -> JsonBool(r.nextBoolean())
        2 -> JsonNumber(listOf(0.0, 1.0, -1.0, 1.5, 2147483648.0, 9007199254740992.0, 1733584666641.0, 4.0, 1e300).random(r))
        3 -> JsonString("")
        4 -> JsonString(listOf("0193a1b2-0002-7000-8000-000000000001", "0193A1B2-0002-7000-8000-000000000001", "x", "a\u0000b", "good", "meaning", "e:1358280").random(r))
        5 -> JsonString("0193a1b2c011-0000-01931f3e-0000-7000-8000-0000000000a1")
        6 -> JsonString("語".repeat(r.nextInt(1, 400)))
        7 -> JsonNumber(r.nextInt(-5, 5).toDouble())
        8 -> JsonString(types.random(r))
        9 -> JsonArray(List(r.nextInt(4)) { randomValue(r, depth + 1) })
        10 -> JsonObject(List(r.nextInt(4)) { listOf("id", "set", "fields", "x", "status", "kind").random(r) to randomValue(r, depth + 1) }.toMap())
        else -> JsonArray(listOf(JsonString("a"), JsonString("a")))
    }

    /** One random edit somewhere in the tree: replace a value, drop or add a member, or retype the op. */
    private fun mutate(root: JsonValue, r: Random): JsonValue {
        val paths = ArrayList<List<Any>>()
        fun collect(v: JsonValue, path: List<Any>) {
            paths += path
            when (v) {
                is JsonObject -> v.members.forEach { (k, x) -> collect(x, path + k) }
                is JsonArray -> v.items.forEachIndexed { i, x -> collect(x, path + i) }
                else -> Unit
            }
        }
        collect(root, emptyList())
        val target = paths[r.nextInt(paths.size)]
        return when (r.nextInt(6)) {
            0 -> setAt(root, listOf("op_type"), JsonString(types.random(r)))
            1 -> edit(root, target) { _ -> null } // remove
            2 -> edit(root, target) { v ->
                if (v is JsonObject) JsonObject(v.members + (listOf("extra", "id", "until", "set", "note_id").random(r) to randomValue(r))) else randomValue(r)
            }
            else -> edit(root, target) { _ -> randomValue(r) }
        }
    }

    private fun setAt(root: JsonValue, path: List<Any>, value: JsonValue): JsonValue = edit(root, path) { value }

    /** [root] with the node at [path] replaced by [f] of it (null = removed from its parent). */
    private fun edit(root: JsonValue, path: List<Any>, f: (JsonValue?) -> JsonValue?): JsonValue {
        if (path.isEmpty()) return f(root) ?: root
        val head = path.first()
        val rest = path.drop(1)
        return when {
            root is JsonObject && head is String -> {
                val m = LinkedHashMap(root.members)
                val cur = m[head]
                val next = if (rest.isEmpty()) f(cur) else cur?.let { edit(it, rest, f) }
                if (next == null) m.remove(head) else m[head] = next
                JsonObject(m)
            }
            root is JsonArray && head is Int && head < root.items.size -> {
                val l = root.items.toMutableList()
                val next = if (rest.isEmpty()) f(l[head]) else edit(l[head], rest, f)
                if (next == null) l.removeAt(head) else l[head] = next
                JsonArray(l)
            }
            else -> root
        }
    }

    private companion object {
        const val MUTANTS = 3000
    }
}
