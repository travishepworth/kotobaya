package kotobaya.android.shell.api.actions

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.store.StoreTransaction

/**
 * The server's request readers (`server/.../notes/Support.kt` `Errors`, `NoteApi` helpers) over the shell's JSON
 * model: the same codes at the same pointers, so the actions group answers `422`s exactly as the server (SHELL.md L-3).
 */

internal val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
internal val KEY_RE = Regex("^[a-z][a-z0-9_]*$")

/** Largest integer a JSON number carries exactly (binary64): beyond it a value is not an integer we can store. */
private const val MAX_SAFE = 9.007199254740991E15

/** A path id that is not a canonical UUID cannot name a row: `404` (server `pathId`). */
internal fun LocalCall.pathId(name: String, what: String): String =
    path(name).takeIf { UUID_RE.matches(it) } ?: throw ProblemException(Problems.notFound("No such $what."))

internal fun escapePointer(s: String): String = s.replace("~", "~0").replace("/", "~1")

private fun codePoints(s: String): Int = s.codePointCount(0, s.length)

/** Members outside [allowed] (document order); names in [immutable] get `immutable`, the rest `unknown_field`. */
internal fun FieldErrors.unknownKeys(o: JsonObject, allowed: Set<String>, immutable: Set<String> = emptySet()) {
    for (k in o.members.keys) {
        when (k) {
            in allowed -> Unit
            in immutable -> add("/${escapePointer(k)}", "immutable", "$k cannot be changed.")
            else -> add("/${escapePointer(k)}", "unknown_field", "Unknown property $k.")
        }
    }
}

/** A string of [min]..[max] code points: not a string `invalid_type`, short `invalid_value`, long `too_long`. */
internal fun FieldErrors.stringValue(v: JsonValue, at: String, min: Int, max: Int): String? {
    val text = (v as? JsonString)?.value
    if (text == null) {
        add(at, "invalid_type", "Must be a string.")
        return null
    }
    val n = codePoints(text)
    if (n < min) {
        add(at, "invalid_value", if (min == 1) "Must not be empty." else "Must be at least $min characters.")
        return null
    }
    if (n > max) {
        add(at, "too_long", "At most $max characters.")
        return null
    }
    return text
}

internal fun FieldErrors.string(o: JsonObject, key: String, min: Int, max: Int, required: Boolean = false): String? {
    val v = o.members[key]
    if (v == null) {
        if (required) add("/$key", "required", "$key is required.")
        return null
    }
    return stringValue(v, "/$key", min, max)
}

internal fun FieldErrors.uuidValue(v: JsonValue, at: String): String? {
    val text = (v as? JsonString)?.value
    if (text == null || !UUID_RE.matches(text)) {
        add(at, "invalid_value", "Must be a lowercase UUID.")
        return null
    }
    return text
}

/** A UUID or `null`; absent → null. */
internal fun FieldErrors.nullableUuid(o: JsonObject, key: String): String? {
    val v = o.members[key] ?: return null
    return if (v == JsonNull) null else uuidValue(v, "/$key")
}

internal fun FieldErrors.enumValue(o: JsonObject, key: String, values: Set<String>, at: String = "/$key", required: Boolean = false): String? {
    val v = o.members[key]
    if (v == null) {
        if (required) add(at, "required", "$key is required.")
        return null
    }
    val text = (v as? JsonString)?.value
    if (text == null || text !in values) {
        add(at, "invalid_value", "Must be one of ${values.joinToString(", ")}.")
        return null
    }
    return text
}

/** An integral JSON number, else null. */
internal fun integral(v: JsonValue?): Long? {
    val d = (v as? JsonNumber)?.value ?: return null
    if (!d.isFinite() || d != Math.floor(d) || d < -MAX_SAFE || d > MAX_SAFE) return null
    return d.toLong()
}

/** An integer in `[min, max]` (server `Errors.long`): not an integer `invalid_type`, outside `out_of_range`. */
internal fun FieldErrors.long(v: JsonValue, at: String, min: Long, max: Long = Long.MAX_VALUE): Long? {
    val n = integral(v)
    if (n == null) {
        add(at, "invalid_type", "Must be an integer.")
        return null
    }
    if (n < min || n > max) {
        add(at, "out_of_range", "Must be at least $min.")
        return null
    }
    return n
}

/** An RFC 3339 instant as epoch ms (server `parseInstant`). */
internal fun FieldErrors.instant(v: JsonValue, at: String): Long? {
    val text = (v as? JsonString)?.value
    if (text == null) {
        add(at, "invalid_type", "Must be an RFC 3339 instant.")
        return null
    }
    return try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (x: DateTimeParseException) {
        add(at, "invalid_value", "Must be an RFC 3339 instant.")
        null
    } catch (x: ArithmeticException) {
        add(at, "invalid_value", "Must be an RFC 3339 instant.")
        null
    }
}

/** Characters ECMA-262 `\s` matches (a tag holding one would fail the op schema's `\S`). */
private fun isEcmaSpace(cp: Int): Boolean =
    cp in 0x09..0x0D || cp == 0x20 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A ||
        cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000 || cp == 0xFEFF

/** Code-point order, as the tag set of an op is sorted (common.schema.json `tags`). */
internal val CodePointOrder: Comparator<String> = Comparator { a, b ->
    var i = 0
    var j = 0
    var r = 0
    while (r == 0 && i < a.length && j < b.length) {
        val x = a.codePointAt(i)
        val y = b.codePointAt(j)
        r = x.compareTo(y)
        i += Character.charCount(x)
        j += Character.charCount(y)
    }
    if (r != 0) r else (a.length - i).compareTo(b.length - j)
}

/** Tags (server `tagList`): 1..64 characters, no whitespace, not `leech`; de-duplicated and sorted by code point. */
internal fun FieldErrors.tagList(node: JsonValue, at: String, minItems: Int, maxItems: Int): List<String>? {
    val items = (node as? JsonArray)?.items
    if (items == null) {
        add(at, "invalid_type", "Must be an array.")
        return null
    }
    if (items.size < minItems || items.size > maxItems) {
        add(at, "out_of_range", "Between $minItems and $maxItems tags.")
        return null
    }
    var ok = true
    val tags = sortedSetOf(CodePointOrder)
    items.forEachIndexed { i, t ->
        val tat = "$at/$i"
        val s = stringValue(t, tat, 1, 64)
        when {
            s == null -> ok = false
            s.codePoints().anyMatch(::isEcmaSpace) -> { add(tat, "invalid_value", "Tags must not contain whitespace."); ok = false }
            s == "leech" -> { add(tat, "invalid_value", "leech is a virtual tag and is never stored."); ok = false }
            else -> tags += s
        }
    }
    return if (ok) tags.toList() else null
}

/** Field values `{key: string}` (server `fieldValues`): keys are field keys, values at most 100000 characters. */
internal fun FieldErrors.fieldValues(node: JsonValue, at: String, minProps: Int): Map<String, String>? {
    val members = (node as? JsonObject)?.members
    if (members == null) {
        add(at, "invalid_type", "Must be an object.")
        return null
    }
    if (members.size < minProps) {
        add(at, "out_of_range", "At least one field.")
        return null
    }
    if (members.size > 100) {
        add(at, "out_of_range", "At most 100 fields.")
        return null
    }
    val out = LinkedHashMap<String, String>()
    for ((k, v) in members) {
        val fat = "$at/${escapePointer(k)}"
        if (!KEY_RE.matches(k) || k.length > 64) {
            add(fat, "invalid_value", "A field key is a lowercase identifier of at most 64 characters.")
            continue
        }
        stringValue(v, fat, 0, 100_000)?.let { out[k] = it }
    }
    return out
}

internal fun jsonObj(vararg members: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*members))

internal fun JsonValue?.text(): String? = (this as? JsonString)?.value

internal fun JsonValue?.isNullish(): Boolean = this == null || this == JsonNull

/**
 * Serializes this group's check-then-mint writes (deleting a deleted note, clearing a cleared status must mint
 * nothing, also for two concurrent retries). The guarantee is the store transaction ([LocalWrites], L-4); the lock
 * keeps it on a store whose transactions do not exclude each other (the single-connection JVM driver of the tests).
 */
private val writeLock = Any()

internal fun <T> LocalWrites<SqlLearningTables>.exclusive(block: (StoreTransaction<SqlLearningTables>) -> WritePlan<SqlLearningTables, T>): T =
    synchronized(writeLock) { write(block) }
