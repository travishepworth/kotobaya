package kotobaya.android.shell.parity

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson

/** JSON helpers of the parity harness, on core's [JsonValue] (the android test classpath has no Jackson). */
object ParityJson {
    fun parse(text: String): JsonValue {
        val r = DefaultCanonicalJson.decode(text)
        return r.value ?: error("not JSON: ${r.error}")
    }

    /** Compact JSON (members sorted; numbers as core encodes them). */
    fun encode(v: JsonValue): String = DefaultCanonicalJson.encode(v)

    fun obj(v: JsonValue?): JsonObject = v as? JsonObject ?: error("expected a JSON object, got $v")

    fun str(v: JsonValue?): String = (v as? JsonString)?.value ?: error("expected a JSON string, got $v")

    fun long(v: JsonValue?): Long = (v as? JsonNumber)?.value?.toLong() ?: error("expected a JSON number, got $v")

    fun arr(v: JsonValue?): List<JsonValue> = (v as? JsonArray)?.items ?: error("expected a JSON array, got $v")

    /** RFC 6901 reference tokens of [pointer] (`""` = none). */
    fun tokens(pointer: String): List<String> {
        if (pointer.isEmpty()) return emptyList()
        require(pointer.startsWith("/")) { "not a JSON Pointer: $pointer" }
        return pointer.substring(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
    }

    fun escape(token: String): String = token.replace("~", "~0").replace("/", "~1")

    /** RFC 6901 lookup without `*`; null when it does not resolve. */
    fun at(root: JsonValue?, pointer: String): JsonValue? {
        var n: JsonValue = root ?: return null
        for (t in tokens(pointer)) {
            n = when (n) {
                is JsonObject -> n.members[t] ?: return null
                is JsonArray -> t.toIntOrNull()?.takeIf { it in n.items.indices && t == it.toString() }?.let { n.items[it] } ?: return null
                else -> return null
            }
        }
        return n
    }

    /** SHELL.md 8.3 rule 6: [root] without the targets of [pointer] (`*` = every member / element); a missing target is fine. */
    fun remove(root: JsonValue, pointer: String): JsonValue {
        if (pointer.isEmpty()) return JsonNull
        return removeAt(root, tokens(pointer))
    }

    private fun removeAt(node: JsonValue, path: List<String>): JsonValue {
        val head = path.first()
        val rest = path.drop(1)
        return when (node) {
            is JsonObject -> {
                val hit: (String) -> Boolean = { k -> head == "*" || k == head }
                val out = LinkedHashMap<String, JsonValue>()
                for ((k, v) in node.members) {
                    when {
                        !hit(k) -> out[k] = v
                        rest.isEmpty() -> Unit
                        else -> out[k] = removeAt(v, rest)
                    }
                }
                JsonObject(out)
            }
            is JsonArray -> {
                val index = head.toIntOrNull()
                val hit: (Int) -> Boolean = { i -> head == "*" || i == index }
                JsonArray(node.items.mapIndexedNotNull { i, v -> if (!hit(i)) v else if (rest.isEmpty()) null else removeAt(v, rest) })
            }
            else -> node
        }
    }

    /** Equal as JSON values: member order irrelevant, numbers compared as binary64 (core's equality). */
    fun equal(a: JsonValue?, b: JsonValue?): Boolean = a == b

    /**
     * Every place where [actual] differs from [expected], as `<pointer>: expected <x>, actual <y>` (a missing member is
     * `absent`). Arrays of different lengths are compared element-wise and the surplus is reported per index.
     */
    fun diff(expected: JsonValue?, actual: JsonValue?, pointer: String = ""): List<String> {
        if (expected == actual) return emptyList()
        val at = pointer.ifEmpty { "\"\"" }
        if (expected is JsonObject && actual is JsonObject) {
            return (expected.members.keys + actual.members.keys).sorted().distinct().flatMap { k ->
                val p = "$pointer/${escape(k)}"
                val e = expected.members[k]
                val a = actual.members[k]
                when {
                    e == null -> listOf("$p: expected absent, actual ${show(a)}")
                    a == null -> listOf("$p: expected ${show(e)}, actual absent")
                    else -> diff(e, a, p)
                }
            }
        }
        if (expected is JsonArray && actual is JsonArray) {
            val n = maxOf(expected.items.size, actual.items.size)
            return (0 until n).flatMap { i ->
                val p = "$pointer/$i"
                val e = expected.items.getOrNull(i)
                val a = actual.items.getOrNull(i)
                when {
                    e == null -> listOf("$p: expected absent, actual ${show(a)}")
                    a == null -> listOf("$p: expected ${show(e)}, actual absent")
                    else -> diff(e, a, p)
                }
            }
        }
        return listOf("$at: expected ${show(expected)}, actual ${show(actual)}")
    }

    fun show(v: JsonValue?): String {
        val s = if (v == null) "absent" else encode(v)
        return if (s.length > 200) s.take(197) + "..." else s
    }
}
