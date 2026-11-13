package kotobaya.sync

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue

/** The first rule an instance breaks. */
public data class SchemaError(
    /** English diagnostic naming the keyword; for logs and `error.message` only. */
    public val message: String,
    /** JSON Pointer (RFC 6901) to the failing value in the instance; `""` = the instance itself. */
    public val pointer: String,
)

/**
 * JSON Schema 2020-12 validation of sync data against the schemas of `contracts/sync`
 * (`op.schema.json`, `common.schema.json`, `batch.schema.json`, `ops/<op_type>.schema.json`), which are
 * embedded in [SyncSchemaTexts] (generated; kept in sync with the files by the jvm test
 * `SyncContractDriftTest`).
 *
 * A small validator for exactly the keywords those schemas use (no dependency):
 * `$ref` (relative file + JSON Pointer fragment), `type`, `const`, `enum`, `minimum`, `maximum`,
 * `exclusiveMinimum`, `exclusiveMaximum`, `minLength`, `maxLength` (code points), `pattern`
 * (unanchored search with ECMA-262 semantics on every platform, see [PortableRegex]), `prefixItems`, `items` (schema or `false`), `minItems`, `maxItems`,
 * `uniqueItems`, `required`, `properties`, `additionalProperties`, `propertyNames`,
 * `minProperties`, `dependentRequired`, `allOf`, `anyOf`, `oneOf`, `if`/`then`/`else`, and boolean
 * schemas. Annotations (`title`, `description`, `examples`, `default`, `$schema`, `$defs`, `$comment`)
 * are ignored. [SUPPORTED_KEYWORDS] + [ANNOTATIONS] is the complete list; a test fails if a schema
 * uses anything else.
 *
 * `"type": "integer"` means (OpCodec KDoc) a number whose double value is integral with magnitude
 * `< 2^53`; `"number"` is any finite number. Equality (`const`, `enum`, `uniqueItems`) is JSON value
 * equality: member order ignored, numbers compared as doubles.
 *
 * Only the first failure is reported, in a fixed order: `$ref`, `type`, `const`, `enum`, numeric,
 * string, array (`prefixItems`, `items`, counts, `uniqueItems`), object (`required`, `properties` in
 * schema order, `additionalProperties` in instance order, `propertyNames`, `minProperties`,
 * `dependentRequired`), `allOf`, `anyOf`, `oneOf`, `if`. A failing `anyOf` reports its deepest branch
 * error if one points below the instance (e.g. `/payload/optimizer_stats/review_count`), else the
 * instance itself; a failing `oneOf` is reported at the instance.
 */
public object SyncSchemas {
    /** Keywords the validator evaluates. */
    public val SUPPORTED_KEYWORDS: Set<String> = setOf(
        "\$ref", "type", "const", "enum", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
        "minLength", "maxLength", "pattern", "prefixItems", "items", "minItems", "maxItems", "uniqueItems",
        "required", "properties", "additionalProperties", "propertyNames", "minProperties",
        "dependentRequired", "allOf", "anyOf", "oneOf", "if", "then", "else",
    )

    /** Keywords without assertion effect. */
    public val ANNOTATIONS: Set<String> = setOf(
        "\$schema", "\$defs", "\$comment", "\$id", "title", "description", "examples", "default",
    )

    private const val TWO_POW_53 = 9007199254740992.0

    private val schemas: Map<String, JsonValue> by lazy {
        SyncSchemaTexts.FILES.mapValues { (name, text) ->
            requireNotNull(DefaultCanonicalJson.decode(text).value) { "embedded schema $name does not parse" }
        }
    }

    private val regexCache: Map<String, Regex> by lazy {
        val out = HashMap<String, Regex>()
        fun walk(v: JsonValue) {
            when (v) {
                is JsonObject -> v.members.forEach { (k, x) ->
                    if (k == "pattern" && x is JsonString) out.getOrPut(x.value) { PortableRegex.compile(x.value) }
                    walk(x)
                }
                is JsonArray -> v.items.forEach(::walk)
                else -> Unit
            }
        }
        schemas.values.forEach(::walk)
        out
    }

    /**
     * Every `$ref` of every embedded file, resolved once to (file it names, target node), keyed by
     * [refKey] of the file holding the `$ref` and its text. Built in one `lazy` (thread-safe, immutable
     * afterwards), so validation never splits a ref string again (BUG-8). A ref that does not resolve
     * is left out, so it fails at validation time exactly as before.
     */
    private val refCache: Map<String, Pair<String, JsonValue>> by lazy {
        val out = HashMap<String, Pair<String, JsonValue>>()
        fun walk(file: String, v: JsonValue) {
            when (v) {
                is JsonObject -> v.members.forEach { (k, x) ->
                    if (k == "\$ref" && x is JsonString) {
                        val key = refKey(file, x.value)
                        if (key !in out) runCatching { resolve(file, x.value) }.onSuccess { out[key] = it }
                    }
                    walk(file, x)
                }
                is JsonArray -> v.items.forEach { walk(file, it) }
                else -> Unit
            }
        }
        schemas.forEach { (file, root) -> walk(file, root) }
        out
    }

    private fun refKey(file: String, ref: String): String = "$file\u0000$ref"

    /** [resolve] through [refCache]; a ref it lacks (one of an ad-hoc schema) is resolved directly. */
    private fun resolveCached(file: String, ref: String): Pair<String, JsonValue> =
        refCache[refKey(file, ref)] ?: resolve(file, ref)

    /** Paths (relative to `contracts/sync`) of the embedded schema files, e.g. `ops/note.created.schema.json`. */
    public val files: Set<String> get() = SyncSchemaTexts.FILES.keys

    /** The parsed embedded schema file [path] (relative to `contracts/sync`), or null. */
    public fun schema(path: String): JsonValue? = schemas[path]

    /**
     * Validates [instance] against [ref]: a file path relative to `contracts/sync`, optionally with a
     * JSON Pointer fragment, e.g. `op.schema.json`, `ops/review.logged.schema.json`,
     * `batch.schema.json#/$defs/PushRequestLenient`. Returns null if valid, else the first error.
     *
     * @throws IllegalArgumentException if [ref] does not name an embedded schema.
     */
    public fun validate(ref: String, instance: JsonValue): SchemaError? {
        val (file, schema) = resolve("", ref)
        return check(schema, file, instance, "")
    }

    /** The schema node [ref] points at (same syntax as [validate]). */
    internal fun resolveRef(ref: String): JsonValue = resolve("", ref).second

    /** Validates against an ad-hoc [schema] whose `$ref`s resolve relative to [baseFile]. */
    internal fun validateWith(schema: JsonValue, baseFile: String, instance: JsonValue): SchemaError? =
        check(schema, baseFile, instance, "")

    /** True for two JSON values that are equal as JSON (member order ignored, numbers as doubles). */
    public fun jsonEquals(a: JsonValue, b: JsonValue): Boolean = when (a) {
        is JsonNumber -> b is JsonNumber && a.value == b.value
        is JsonArray -> b is JsonArray && a.items.size == b.items.size &&
            a.items.indices.all { jsonEquals(a.items[it], b.items[it]) }
        is JsonObject -> b is JsonObject && a.members.size == b.members.size &&
            a.members.all { (k, v) -> b.members[k]?.let { jsonEquals(v, it) } ?: false }
        else -> a == b
    }

    private fun resolve(baseFile: String, ref: String): Pair<String, JsonValue> {
        val hash = ref.indexOf('#')
        val path = if (hash < 0) ref else ref.substring(0, hash)
        val fragment = if (hash < 0) "" else ref.substring(hash + 1)
        val file = if (path.isEmpty()) baseFile else joinPath(baseFile, path)
        var node: JsonValue = requireNotNull(schemas[file]) { "unknown schema file '$file' (ref '$ref')" }
        if (fragment.isNotEmpty()) {
            require(fragment.startsWith("/")) { "unsupported fragment in '$ref'" }
            for (raw in fragment.substring(1).split('/')) {
                val token = raw.replace("~1", "/").replace("~0", "~")
                node = when (node) {
                    is JsonObject -> node.members[token]
                    is JsonArray -> token.toIntOrNull()?.let { node.items.getOrNull(it) }
                    else -> null
                } ?: throw IllegalArgumentException("unresolvable ref '$ref'")
            }
        }
        return file to node
    }

    private fun joinPath(baseFile: String, rel: String): String {
        val parts = baseFile.split('/').dropLast(1).toMutableList()
        for (seg in rel.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun child(ptr: String, token: String): String =
        ptr + "/" + token.replace("~", "~0").replace("/", "~1")

    private fun isInteger(v: JsonValue): Boolean =
        v is JsonNumber && v.value == kotlin.math.floor(v.value) && kotlin.math.abs(v.value) < TWO_POW_53

    private fun typeMatches(type: String, v: JsonValue): Boolean = when (type) {
        "null" -> v == JsonNull
        "boolean" -> v is JsonBool
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonString
        "number" -> v is JsonNumber
        "integer" -> isInteger(v)
        else -> throw IllegalArgumentException("unknown type '$type' in schema")
    }

    private fun num(v: JsonValue?): Double? = (v as? JsonNumber)?.value

    private fun codePoints(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) i++
            i++
            n++
        }
        return n
    }

    private fun describe(v: JsonValue): String {
        val text = DefaultCanonicalJson.encode(v)
        return if (text.length > 60) text.substring(0, 57) + "..." else text
    }

    /**
     * The first error of [inst] against [schema] (message and pointer), or null. Sub-schemas whose
     * error is never reported (`if` conditions, `oneOf` branches, `anyOf` branches until every one
     * failed) are evaluated by [passes] instead (BUG-8).
     */
    private fun check(schema: JsonValue, file: String, inst: JsonValue, ptr: String): SchemaError? =
        eval(schema, file, inst, ptr, true)

    /** True iff [check] of the same arguments would return null; builds no message and no pointer. */
    private fun passes(schema: JsonValue, file: String, inst: JsonValue): Boolean = eval(schema, file, inst, "", false) == null

    /** What [eval] returns for a failure in verdict-only mode (never escapes this object). */
    private val FAIL = SchemaError("", "")

    /**
     * The validator. With [full] it returns the first error with its diagnostic; without, [FAIL] or
     * null. Both modes evaluate the same predicates in the same order, so the verdict is identical;
     * the verdict-only mode skips what only feeds the diagnostic (the message, the pointer, the
     * `anyOf` deepest-branch choice). `anyOf` computes its branch diagnostics only once every branch
     * has failed, which picks the same error the old one-pass loop picked.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun eval(schema: JsonValue, file: String, inst: JsonValue, ptr: String, full: Boolean): SchemaError? {
        if (schema is JsonBool) return if (schema.value) null else if (full) SchemaError("not allowed here", ptr) else FAIL
        val s = (schema as? JsonObject)?.members ?: throw IllegalArgumentException("schema is not an object at $file")
        fun at(token: String): String = if (full) child(ptr, token) else ptr

        s["\$ref"]?.let { ref ->
            val (f, target) = resolveCached(file, (ref as JsonString).value)
            eval(target, f, inst, ptr, full)?.let { return it }
        }
        s["type"]?.let { t ->
            val types = when (t) {
                is JsonString -> listOf(t.value)
                is JsonArray -> t.items.map { (it as JsonString).value }
                else -> throw IllegalArgumentException("bad type keyword")
            }
            if (types.none { typeMatches(it, inst) }) {
                if (!full) return FAIL
                val hint = if ("integer" in types && inst is JsonNumber) " (an integral value with magnitude < 2^53)" else ""
                return SchemaError("expected ${types.joinToString(" or ")}$hint, got ${describe(inst)}", ptr)
            }
        }
        s["const"]?.let { c ->
            if (!jsonEquals(c, inst)) return if (full) SchemaError("must be ${describe(c)}", ptr) else FAIL
        }
        s["enum"]?.let { e ->
            if ((e as JsonArray).items.none { jsonEquals(it, inst) }) {
                return if (full) SchemaError("must be one of ${describe(e)}, got ${describe(inst)}", ptr) else FAIL
            }
        }
        if (inst is JsonNumber) {
            val x = inst.value
            num(s["minimum"])?.let { if (x < it) return if (full) SchemaError("must be >= ${JsNumberFormat.format(it)}", ptr) else FAIL }
            num(s["maximum"])?.let { if (x > it) return if (full) SchemaError("must be <= ${JsNumberFormat.format(it)}", ptr) else FAIL }
            num(s["exclusiveMinimum"])?.let { if (x <= it) return if (full) SchemaError("must be > ${JsNumberFormat.format(it)}", ptr) else FAIL }
            num(s["exclusiveMaximum"])?.let { if (x >= it) return if (full) SchemaError("must be < ${JsNumberFormat.format(it)}", ptr) else FAIL }
        }
        if (inst is JsonString) {
            val len by lazy { codePoints(inst.value) }
            num(s["minLength"])?.let { if (len < it) return if (full) SchemaError("shorter than ${it.toLong()} characters", ptr) else FAIL }
            num(s["maxLength"])?.let { if (len > it) return if (full) SchemaError("longer than ${it.toLong()} characters", ptr) else FAIL }
            (s["pattern"] as? JsonString)?.let { p ->
                val re = regexCache[p.value] ?: PortableRegex.compile(p.value)
                if (!re.containsMatchIn(inst.value)) return if (full) SchemaError("does not match ${p.value}", ptr) else FAIL
            }
        }
        if (inst is JsonArray) {
            val items = inst.items
            var prefix = 0
            (s["prefixItems"] as? JsonArray)?.let { pi ->
                prefix = pi.items.size
                for (i in 0 until minOf(prefix, items.size)) {
                    eval(pi.items[i], file, items[i], at(i.toString()), full)?.let { return it }
                }
            }
            s["items"]?.let { itemSchema ->
                for (i in prefix until items.size) {
                    if (itemSchema is JsonBool && !itemSchema.value) {
                        return if (full) SchemaError("at most $prefix items allowed", ptr) else FAIL
                    }
                    eval(itemSchema, file, items[i], at(i.toString()), full)?.let { return it }
                }
            }
            num(s["minItems"])?.let { if (items.size < it) return if (full) SchemaError("fewer than ${it.toLong()} items", ptr) else FAIL }
            num(s["maxItems"])?.let { if (items.size > it) return if (full) SchemaError("more than ${it.toLong()} items", ptr) else FAIL }
            if (s["uniqueItems"] == JsonBool(true)) {
                for (i in items.indices) for (j in 0 until i) {
                    if (jsonEquals(items[i], items[j])) return if (full) SchemaError("items $j and $i are equal", ptr) else FAIL
                }
            }
        }
        if (inst is JsonObject) {
            val m = inst.members
            (s["required"] as? JsonArray)?.items?.forEach { r ->
                val name = (r as JsonString).value
                if (name !in m) return if (full) SchemaError("missing required property '$name'", ptr) else FAIL
            }
            val props = (s["properties"] as? JsonObject)?.members ?: emptyMap()
            for ((name, sub) in props) {
                val v = m[name] ?: continue
                eval(sub, file, v, at(name), full)?.let { return it }
            }
            s["additionalProperties"]?.let { ap ->
                for ((name, v) in m) {
                    if (name in props) continue
                    if (ap is JsonBool && !ap.value) return if (full) SchemaError("unknown property '$name'", child(ptr, name)) else FAIL
                    eval(ap, file, v, at(name), full)?.let { return it }
                }
            }
            s["propertyNames"]?.let { pn ->
                for (name in m.keys) {
                    eval(pn, file, JsonString(name), at(name), full)?.let {
                        return if (full) SchemaError("property name '$name': ${it.message}", child(ptr, name)) else FAIL
                    }
                }
            }
            num(s["minProperties"])?.let { if (m.size < it) return if (full) SchemaError("fewer than ${it.toLong()} properties", ptr) else FAIL }
            (s["dependentRequired"] as? JsonObject)?.members?.forEach { (name, deps) ->
                if (name in m) {
                    for (d in (deps as JsonArray).items) {
                        val dn = (d as JsonString).value
                        if (dn !in m) return if (full) SchemaError("'$name' requires '$dn'", ptr) else FAIL
                    }
                }
            }
        }
        (s["allOf"] as? JsonArray)?.items?.forEach { sub -> eval(sub, file, inst, ptr, full)?.let { return it } }
        (s["anyOf"] as? JsonArray)?.let { any ->
            if (any.items.any { passes(it, file, inst) }) return@let
            if (!full) return FAIL
            // every branch failed: their diagnostics, in branch order as before
            val errors = any.items.map { sub -> checkNotNull(eval(sub, file, inst, ptr, true)) }
            // the deepest branch error points closest to the culprit (an object branch whose member is wrong)
            val deepest = errors.maxByOrNull { it.pointer.length }
            if (deepest != null && deepest.pointer.length > ptr.length) return deepest
            return SchemaError("matches none of the allowed shapes (anyOf): ${describe(inst)}", ptr)
        }
        (s["oneOf"] as? JsonArray)?.let { one ->
            val n = one.items.count { passes(it, file, inst) }
            if (n != 1) return if (full) SchemaError("must match exactly one shape (oneOf), matches $n", ptr) else FAIL
        }
        s["if"]?.let { cond ->
            val branch = if (passes(cond, file, inst)) s["then"] else s["else"]
            if (branch != null) eval(branch, file, inst, ptr, full)?.let { return it }
        }
        return null
    }
}
