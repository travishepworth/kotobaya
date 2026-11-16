package kotobaya.parity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import kotobaya.server.json

/** One raw answer: status, header fields (names lower-case), body text (null when empty). */
data class RawResponse(val status: Int, val headers: Map<String, String>, val body: String?)

/**
 * SHELL.md 8.3, for one scenario. Stateful: the `id` and `hlc` counters (and the value -> placeholder maps) run
 * across all steps, so one instance normalises the steps of one scenario in order.
 */
class Normalizer(private val knownIds: Set<String>, private val mintingDevice: String) {
    private val ids = LinkedHashMap<String, String>()
    private val hlcs = LinkedHashMap<String, String>()
    private val nodes = JsonNodeFactory.instance

    /** Rules 1–6: `{status, headers, body}`. */
    fun step(raw: RawResponse, ignore: List<String> = emptyList()): ObjectNode {
        val headers = nodes.objectNode()
        val contentType = raw.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
        contentType?.let { headers.put("content-type", it) }
        raw.headers["retry-after"]?.let { headers.put("retry-after", it) }
        // Rule 5: content-location first, then the body.
        raw.headers["content-location"]?.let { headers.put("content-location", string(it)) }
        var body: JsonNode = parseBody(raw.body)
        body = dropMember(body, "generated_at")
        if (contentType == "application/problem+json" && body is ObjectNode) problem(body)
        body = traverse(body)
        for (p in ignore) body = remove(body, p)
        return nodes.objectNode().apply {
            put("status", raw.status)
            set<JsonNode>("headers", sorted(headers))
            set<JsonNode>("body", body)
        }
    }

    private fun parseBody(text: String?): JsonNode {
        if (text.isNullOrEmpty()) return NullNode.instance
        return runCatching { json.readTree(text) }.getOrNull() ?: TextNode(text)
    }

    private fun problem(body: ObjectNode) {
        body.remove(listOf("request_id", "title", "detail"))
        val errors = body["errors"] as? ArrayNode ?: return
        val items = errors.map { e -> (e as? ObjectNode)?.apply { remove("detail") } ?: e }
            .sortedWith(compareBy<JsonNode>({ it["pointer"]?.asText() ?: "" }, { it["code"]?.asText() ?: "" }))
        errors.removeAll()
        items.forEach { errors.add(it) }
    }

    private fun dropMember(node: JsonNode, name: String): JsonNode {
        when (node) {
            is ObjectNode -> {
                node.remove(name)
                node.fieldNames().asSequence().toList().forEach { k -> node.set<JsonNode>(k, dropMember(node[k], name)) }
            }
            is ArrayNode -> for (i in 0 until node.size()) node.set(i, dropMember(node[i], name))
        }
        return node
    }

    /** Depth-first, object members in ascending key order, arrays in index order; objects rebuilt sorted. */
    private fun traverse(node: JsonNode): JsonNode = when {
        node.isObject -> nodes.objectNode().also { out ->
            node.fieldNames().asSequence().toList().sorted().forEach { k -> out.set<JsonNode>(k, traverse(node[k])) }
        }
        node.isArray -> nodes.arrayNode().also { out -> node.forEach { out.add(traverse(it)) } }
        node.isTextual -> TextNode(string(node.asText()))
        else -> node
    }

    /** Rules 4 and 5 on one string value. */
    internal fun string(s: String): String {
        if (s == mintingDevice) return "<device>"
        HLC.matchEntire(s)?.let { m ->
            if (m.groupValues[1] == mintingDevice) return hlcs.getOrPut(s) { "<hlc${hlcs.size + 1}>" }
        }
        return UUID.replace(s) { m ->
            val v = m.value
            if (v in knownIds || v.startsWith(BUILT_IN)) v else ids.getOrPut(v) { "<id${ids.size + 1}>" }
        }
    }

    private fun sorted(o: ObjectNode): ObjectNode =
        nodes.objectNode().also { out -> o.fieldNames().asSequence().toList().sorted().forEach { out.set<JsonNode>(it, o[it]) } }

    companion object {
        const val BUILT_IN = "00000000-0000-7000-8000-"
        private const val U = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val UUID = Regex("(?<![0-9a-f])$U(?![0-9a-f])")
        val HLC = Regex("^[0-9a-f]{12}-[0-9a-f]{4}-($U)$")

        /** Rule 6: removes the target of [pointer] (`*` = every member / element); a missing target is fine. */
        fun remove(root: JsonNode, pointer: String): JsonNode {
            if (pointer.isEmpty()) return NullNode.instance
            removeAt(root, tokens(pointer))
            return root
        }

        private fun removeAt(node: JsonNode, path: List<String>) {
            val head = path.first()
            val rest = path.drop(1)
            val children: List<Pair<String, JsonNode>> = when {
                node is ObjectNode && head == "*" -> node.fieldNames().asSequence().toList().map { it to node[it] }
                node is ObjectNode -> listOfNotNull(node[head]?.let { head to it })
                node is ArrayNode && head == "*" -> (0 until node.size()).map { it.toString() to node[it] }
                node is ArrayNode -> head.toIntOrNull()?.takeIf { it in 0 until node.size() }?.let { listOf(head to node[it]) }.orEmpty()
                else -> emptyList()
            }
            if (rest.isNotEmpty()) {
                children.forEach { removeAt(it.second, rest) }
                return
            }
            when (node) {
                is ObjectNode -> children.forEach { node.remove(it.first) }
                is ArrayNode -> children.map { it.first.toInt() }.sortedDescending().forEach { node.remove(it) }
                else -> Unit
            }
        }

        fun tokens(pointer: String): List<String> {
            require(pointer.startsWith("/")) { "not a JSON Pointer: $pointer" }
            return pointer.substring(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
        }

        /** RFC 6901 lookup without `*`; null when it does not resolve. */
        fun at(root: JsonNode?, pointer: String): JsonNode? {
            if (root == null) return null
            if (pointer.isEmpty()) return root
            var n: JsonNode = root
            for (t in tokens(pointer)) {
                n = when {
                    n.isObject -> n.get(t) ?: return null
                    n.isArray -> t.toIntOrNull()?.takeIf { it in 0 until n.size() && t == it.toString() }?.let { n[it] } ?: return null
                    else -> return null
                }
            }
            return n
        }

        /** Equal as JSON values: member order irrelevant, numbers compared as binary64. */
        fun jsonEqual(a: JsonNode?, b: JsonNode?): Boolean {
            if (a == null || b == null) return a == null && b == null
            if (a.isNumber && b.isNumber) return a.asDouble() == b.asDouble()
            if (a.isObject && b.isObject) {
                if (a.size() != b.size()) return false
                return a.fieldNames().asSequence().all { k -> b.has(k) && jsonEqual(a[k], b[k]) }
            }
            if (a.isArray && b.isArray) return a.size() == b.size() && (0 until a.size()).all { jsonEqual(a[it], b[it]) }
            if (a.nodeType != b.nodeType) return false
            return a == b
        }
    }
}
