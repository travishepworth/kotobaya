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

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun check(schema: JsonValue, file: String, inst: JsonValue, ptr: String): SchemaError? {
        if (schema is JsonBool) return if (schema.value) null else SchemaError("not allowed here", ptr)
        val s = (schema as? JsonObject)?.members ?: throw IllegalArgumentException("schema is not an object at $file")

        s["\$ref"]?.let { ref ->
            val (f, target) = resolve(file, (ref as JsonString).value)
            check(target, f, inst, ptr)?.let { return it }
        }
        s["type"]?.let { t ->
            val types = when (t) {
                is JsonString -> listOf(t.value)
                is JsonArray -> t.items.map { (it as JsonString).value }
                else -> throw IllegalArgumentException("bad type keyword")
            }
            if (types.none { typeMatches(it, inst) }) {
                val hint = if ("integer" in types && inst is JsonNumber) " (an integral value with magnitude < 2^53)" else ""
                return SchemaError("expected ${types.joinToString(" or ")}$hint, got ${describe(inst)}", ptr)
            }
        }
        s["const"]?.let { c ->
            if (!jsonEquals(c, inst)) return SchemaError("must be ${describe(c)}", ptr)
        }
        s["enum"]?.let { e ->
            if ((e as JsonArray).items.none { jsonEquals(it, inst) }) {
                return SchemaError("must be one of ${describe(e)}, got ${describe(inst)}", ptr)
            }
        }
        if (inst is JsonNumber) {
            val x = inst.value
            num(s["minimum"])?.let { if (x < it) return SchemaError("must be >= ${JsNumberFormat.format(it)}", ptr) }
            num(s["maximum"])?.let { if (x > it) return SchemaError("must be <= ${JsNumberFormat.format(it)}", ptr) }
            num(s["exclusiveMinimum"])?.let { if (x <= it) return SchemaError("must be > ${JsNumberFormat.format(it)}", ptr) }
            num(s["exclusiveMaximum"])?.let { if (x >= it) return SchemaError("must be < ${JsNumberFormat.format(it)}", ptr) }
        }
        if (inst is JsonString) {
            val len by lazy { codePoints(inst.value) }
            num(s["minLength"])?.let { if (len < it) return SchemaError("shorter than ${it.toLong()} characters", ptr) }
            num(s["maxLength"])?.let { if (len > it) return SchemaError("longer than ${it.toLong()} characters", ptr) }
            (s["pattern"] as? JsonString)?.let { p ->
                val re = regexCache[p.value] ?: PortableRegex.compile(p.value)
                if (!re.containsMatchIn(inst.value)) return SchemaError("does not match ${p.value}", ptr)
            }
        }
        if (inst is JsonArray) {
            val items = inst.items
            var prefix = 0
            (s["prefixItems"] as? JsonArray)?.let { pi ->
                prefix = pi.items.size
                for (i in 0 until minOf(prefix, items.size)) {
                    check(pi.items[i], file, items[i], child(ptr, i.toString()))?.let { return it }
                }
            }
            s["items"]?.let { itemSchema ->
                for (i in prefix until items.size) {
                    if (itemSchema is JsonBool && !itemSchema.value) {
                        return SchemaError("at most $prefix items allowed", ptr)
                    }
                    check(itemSchema, file, items[i], child(ptr, i.toString()))?.let { return it }
                }
            }
            num(s["minItems"])?.let { if (items.size < it) return SchemaError("fewer than ${it.toLong()} items", ptr) }
            num(s["maxItems"])?.let { if (items.size > it) return SchemaError("more than ${it.toLong()} items", ptr) }
            if (s["uniqueItems"] == JsonBool(true)) {
                for (i in items.indices) for (j in 0 until i) {
                    if (jsonEquals(items[i], items[j])) return SchemaError("items $j and $i are equal", ptr)
                }
            }
        }
        if (inst is JsonObject) {
            val m = inst.members
            (s["required"] as? JsonArray)?.items?.forEach { r ->
                val name = (r as JsonString).value
                if (name !in m) return SchemaError("missing required property '$name'", ptr)
            }
            val props = (s["properties"] as? JsonObject)?.members ?: emptyMap()
            for ((name, sub) in props) {
                val v = m[name] ?: continue
                check(sub, file, v, child(ptr, name))?.let { return it }
            }
            s["additionalProperties"]?.let { ap ->
                for ((name, v) in m) {
                    if (name in props) continue
                    if (ap is JsonBool && !ap.value) return SchemaError("unknown property '$name'", child(ptr, name))
                    check(ap, file, v, child(ptr, name))?.let { return it }
                }
            }
            s["propertyNames"]?.let { pn ->
                for (name in m.keys) {
                    check(pn, file, JsonString(name), child(ptr, name))?.let {
                        return SchemaError("property name '$name': ${it.message}", child(ptr, name))
                    }
                }
            }
            num(s["minProperties"])?.let { if (m.size < it) return SchemaError("fewer than ${it.toLong()} properties", ptr) }
            (s["dependentRequired"] as? JsonObject)?.members?.forEach { (name, deps) ->
                if (name in m) {
                    for (d in (deps as JsonArray).items) {
                        val dn = (d as JsonString).value
                        if (dn !in m) return SchemaError("'$name' requires '$dn'", ptr)
                    }
                }
            }
        }
        (s["allOf"] as? JsonArray)?.items?.forEach { sub -> check(sub, file, inst, ptr)?.let { return it } }
        (s["anyOf"] as? JsonArray)?.let { any ->
            val errors = ArrayList<SchemaError>()
            for (sub in any.items) errors.add(check(sub, file, inst, ptr) ?: return@let)
            // the deepest branch error points closest to the culprit (an object branch whose member is wrong)
            val deepest = errors.maxByOrNull { it.pointer.length }
            if (deepest != null && deepest.pointer.length > ptr.length) return deepest
            return SchemaError("matches none of the allowed shapes (anyOf): ${describe(inst)}", ptr)
        }
        (s["oneOf"] as? JsonArray)?.let { one ->
            val n = one.items.count { check(it, file, inst, ptr) == null }
            if (n != 1) return SchemaError("must match exactly one shape (oneOf), matches $n", ptr)
        }
        s["if"]?.let { cond ->
            val branch = if (check(cond, file, inst, ptr) == null) s["then"] else s["else"]
            if (branch != null) check(branch, file, inst, ptr)?.let { return it }
        }
        return null
    }
}
