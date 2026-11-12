package kotobaya.sync

import kotobaya.contracts.Uuid
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue

/** Result of [PushBodyParser.parse]. */
public sealed interface PushBody {
    /** The whole request fails (HTTP 400): nothing is applied. [message] is an English diagnostic. */
    public data class BadRequest(
        /** Why; for logs and the problem body only. */
        public val message: String,
    ) : PushBody

    /** The body passes `PushRequestLenient`; each element is then decided on its own, in order. */
    public data class Ops(
        /** One entry per element of `ops`, in array order (1..500 entries). */
        public val elements: List<PushElement>,
    ) : PushBody
}

/** One element of `PushRequest.ops` after the duplicate-tolerant parse (SYNC.md 5.2 "Parsing the body"). */
public sealed interface PushElement {
    /** The element's single, valid `op_id` member: the result's `op_id`. */
    public val opId: Uuid

    /**
     * The element repeats a key in some object (envelope, payload, any depth). After step 1
     * (`duplicate` if `op_id` is already logged) the server answers `rejected` / `schema_invalid`,
     * **before** `op_too_large`: the element has no canonical form.
     */
    public data class DuplicateKey(
        override val opId: Uuid,
        /** The element's source text, as sent. */
        public val sourceText: String,
    ) : PushElement

    /**
     * A duplicate-free element. Next: step 1, then `op_too_large` with
     * `DefaultOpCodec.rawSizeBytes(tree)`, then `DefaultOpCodec.decodeTree(tree, OpSource.PUSHED)`
     * and the rest of SYNC.md 5.3.
     */
    public data class Parsed(
        override val opId: Uuid,
        /** The element as a tree. */
        public val tree: JsonObject,
    ) : PushElement
}

/**
 * The server's syntax-only, duplicate-tolerant parse of a push body (SYNC.md 5.2 "Parsing the body",
 * C-2; FOLLOWUPS "T006 -> T104"). [DefaultCanonicalJson.decode] rejects any duplicate key, which
 * would turn one bad op into a whole-body failure; this parse instead reports it per element.
 * Mirrors `push_body_verdict` of `contracts/sync/tools/validate.py` and the verdicts of
 * `contracts/sync/examples/raw-push/`.
 *
 * [PushBody.BadRequest] when: not JSON (RFC 8259, duplicates allowed); not an object; a key repeated
 * at the top level of the body; the body (duplicates collapsed, later member wins) fails
 * `batch.schema.json#/$defs/PushRequestLenient`; an element repeats its `op_id` member; or two
 * elements share an `op_id` with different contents (equal = both duplicate-free and equal as JSON
 * values, or byte-identical source texts).
 */
public object PushBodyParser {
    private const val ELEMENT_DEPTH = 2

    /** Parses [text]; never throws. */
    public fun parse(text: String): PushBody {
        val p = JsonParser(text, allowDuplicateKeys = true)
        val top = LinkedHashMap<String, JsonValue>()
        val elements = ArrayList<RawElement>()
        try {
            p.skipWs()
            if (p.atEnd() || p.peek() != '{') return PushBody.BadRequest("the body must be a JSON object")
            p.expect('{')
            p.skipWs()
            if (p.peek() == '}') {
                p.pos++
            } else {
                while (true) {
                    p.skipWs()
                    if (p.peek() != '"') p.fail("expected a member name")
                    val key = p.parseString()
                    p.skipWs()
                    p.expect(':')
                    p.skipWs()
                    if (key in top) return PushBody.BadRequest("duplicate top-level key \"$key\"")
                    top[key] = if (key == "ops" && p.peek() == '[') parseOps(p, elements) else p.parseValue(1)
                    p.skipWs()
                    when (p.peek()) {
                        ',' -> p.pos++
                        '}' -> {
                            p.pos++
                            break
                        }
                        else -> p.fail("expected ',' or '}'")
                    }
                }
            }
            p.skipWs()
            if (!p.atEnd()) p.fail("trailing content after the JSON value")
        } catch (e: JsonParseException) {
            return PushBody.BadRequest("not JSON: ${e.message} at ${e.offset}")
        }
        SyncSchemas.validate("batch.schema.json#/\$defs/PushRequestLenient", JsonObject(top))?.let {
            return PushBody.BadRequest("PushRequestLenient: ${it.message} at '${it.pointer}'")
        }
        val seen = HashMap<String, RawElement>()
        val out = ArrayList<PushElement>(elements.size)
        for ((i, el) in elements.withIndex()) {
            if (el.opIdRepeated) return PushBody.BadRequest("ops[$i] repeats its op_id member")
            val opId = ((el.tree as JsonObject).members.getValue("op_id") as JsonString).value
            val prev = seen[opId]
            if (prev != null && !sameContents(prev, el)) {
                return PushBody.BadRequest("ops[$i] reuses op_id $opId with different contents")
            }
            seen[opId] = el
            out.add(if (el.hasDuplicate) PushElement.DuplicateKey(opId, el.source) else PushElement.Parsed(opId, el.tree))
        }
        return PushBody.Ops(out)
    }

    private class RawElement(val tree: JsonValue, val source: String, val hasDuplicate: Boolean, val opIdRepeated: Boolean)

    private fun sameContents(a: RawElement, b: RawElement): Boolean =
        a.source == b.source || (!a.hasDuplicate && !b.hasDuplicate && SyncSchemas.jsonEquals(a.tree, b.tree))

    private fun parseOps(p: JsonParser, out: MutableList<RawElement>): JsonArray {
        p.expect('[')
        p.skipWs()
        if (p.peek() == ']') {
            p.pos++
            return JsonArray(emptyList())
        }
        while (true) {
            p.skipWs()
            val start = p.pos
            p.sawDuplicate = false
            p.duplicateKeys.clear()
            val v = p.parseValue(ELEMENT_DEPTH)
            val opIdRepeated = p.duplicateKeys.any { it.first == ELEMENT_DEPTH && it.second == "op_id" }
            out.add(RawElement(v, p.text.substring(start, p.pos), p.sawDuplicate, opIdRepeated))
            p.skipWs()
            when (p.peek()) {
                ',' -> p.pos++
                ']' -> {
                    p.pos++
                    return JsonArray(out.map { it.tree })
                }
                else -> p.fail("expected ',' or ']'")
            }
        }
    }
}
