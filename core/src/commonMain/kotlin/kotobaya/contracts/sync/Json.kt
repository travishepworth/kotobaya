package kotobaya.contracts.sync

/**
 * A JSON value, independent of any JSON library, so that op payloads can be held, compared and
 * re-encoded identically on JVM, Android and JS.
 *
 * Equality is structural ([JsonObject] equality ignores member order, as Kotlin `Map` equality
 * does). Encoding is defined by [CanonicalJson].
 */
public sealed interface JsonValue

/** JSON `null`. */
public data object JsonNull : JsonValue

/** JSON `true` / `false`. */
public data class JsonBool(
    /** The value. */
    public val value: Boolean,
) : JsonValue

/**
 * A JSON number, held as a double.
 *
 * Every number in a v1 op is either an integer with magnitude `< 2^53` (instants, `entry_seq`,
 * offsets, counts, versions) or a finite double (`desired_retention`, `easy_days`, `weights`), so a
 * double represents all of them exactly. [CanonicalJson.decode] accepts any finite number (an
 * integral double `>= 2^53` such as `1e20` is a valid JSON number and round-trips). The `< 2^53`
 * limit applies only to **integer-typed** schema properties and is enforced by [OpCodec] validation.
 */
public data class JsonNumber(
    /** The value; always finite (NaN and ±Infinity are not JSON). */
    public val value: Double,
) : JsonValue

/** A JSON string. Never contains an unpaired UTF-16 surrogate (a decoder rejects one). */
public data class JsonString(
    /** The unescaped value. */
    public val value: String,
) : JsonValue

/** A JSON array. */
public data class JsonArray(
    /** The elements, in order. */
    public val items: List<JsonValue>,
) : JsonValue

/**
 * A JSON object. [members] keeps insertion/document order (use `LinkedHashMap` / `mapOf`), but that
 * order carries no meaning: equality ignores it and [CanonicalJson.encode] sorts members (canonical
 * member order). A decoder rejects an object with a duplicate key, so a [JsonObject] never has
 * one. (The server's push-body parse is different: it tolerates duplicates in order to report them
 * per op; SYNC.md 5.2 "Parsing the body", C-2.)
 */
public data class JsonObject(
    /** The members, in document order. */
    public val members: Map<String, JsonValue>,
) : JsonValue

/** Why a text could not be decoded as JSON. */
public data class JsonSyntaxError(
    /** English diagnostic, for logs only. */
    public val message: String,
    /** UTF-16 offset in the input where the error was detected. */
    public val offset: Int,
)

/** Result of [CanonicalJson.decode]: exactly one of [value] and [error] is non-null. */
public data class JsonDecodeResult(
    /** The decoded value, or null on error. */
    public val value: JsonValue?,
    /** The error, or null on success. */
    public val error: JsonSyntaxError?,
)

/**
 * The one JSON text form used for ops: **compact canonical JSON**. It is what a client pushes
 * (`PushRequest.ops[i]`), what `max_op_bytes` is measured on (SYNC.md 5.4) and what a device stores
 * in `pending_op`. Server and clients compute an op's size as
 * `utf8Size(encode(op))` (= [sizeOf] of its tree), so they always agree, whatever member order or
 * whitespace a sender used.
 *
 * Encoding rules (all normative). They make `encode` a function of the **value**: `a == b` (structural,
 * order-insensitive equality) ⇒ `encode(a) == encode(b)`, on every platform; the converse holds
 * except that `-0.0` and `0.0` both encode as `0`.
 * - No whitespace anywhere outside strings.
 * - **Canonical member order:** at every depth (envelope, payload, nested objects), object members
 *   sorted by key in ascending order of UTF-16 code units (as RFC 8785 JCS; all v1 keys are ASCII, so
 *   this is byte order). Example: `{"op_id":…,"device_id":…}` is written `{"device_id":…,"op_id":…}`.
 *   Arrays keep their order.
 * - Strings: `"` → `\"`, `\` → `\\`, U+0008 → `\b`, U+000C → `\f`, U+000A → `\n`, U+000D → `\r`,
 *   U+0009 → `\t`, other code points below U+0020 → `\u00XX` with lowercase hex. Every other code
 *   point, including `/`, U+007F and all non-ASCII text, is written **raw** (UTF-8 in bytes), never
 *   `\u`-escaped. A string with an unpaired surrogate cannot be encoded (IllegalArgumentException).
 * - Numbers: exactly the ECMAScript `Number::toString` form, which is what `JSON.stringify` produces:
 *   shortest round-trip digits; integral magnitudes `< 1e21` as plain digits (`1`, not `1.0`;
 *   `1e20` → `100000000000000000000`); `-0.0` → `0`; exponent form `1e+21` / `1e-7` exactly as
 *   JavaScript prints it. JVM code MUST NOT use `Double.toString`, which differs.
 * - `true`, `false`, `null` as literals.
 *
 * Example: `{"a":[1,0.9,"食べる\n"],"b":null}` is canonical; `{"b":null, "a": [1.0]}` is not (its
 * canonical form is `{"a":[1],"b":null}`).
 */
public interface CanonicalJson {
    /**
     * Parses any RFC 8259 JSON text (whitespace allowed, any escapes). A number literal becomes the
     * nearest double (as `JSON.parse` does). Rejects: trailing content after the value, duplicate
     * object keys, unpaired surrogates (raw or `\u`-escaped), numbers that overflow a double
     * (`1e400`). Never throws.
     *
     * Duplicate keys (C-2): because this rejects them, the server MUST NOT use it to split a push
     * body. It parses the body with a syntax-only, duplicate-tolerant parse of its own (SYNC.md 5.2
     * "Parsing the body"): a duplicate at the top level of the body, or the same `op_id` twice with
     * different contents, is HTTP 400; a duplicate anywhere inside `ops[i]` is a per-op `rejected` /
     * `schema_invalid`, decided before `op_too_large` (such an element has no canonical size). Only
     * elements free of duplicates are handed to [sizeOf] / `OpCodec.decodeTree`. Clients push exactly
     * [encode] output, which never contains a duplicate key.
     */
    public fun decode(text: String): JsonDecodeResult

    /**
     * Encodes [value] canonically (rules above). `decode(encode(v)).value == v` for every encodable
     * `v` (finite numbers, no unpaired surrogates), including integral doubles `>= 2^53`, except that a
     * `-0.0` comes back as `0.0`.
     */
    public fun encode(value: JsonValue): String

    /**
     * `utf8Size(encode(value))`, the size measure of SYNC.md 5.3/5.4 (C-1e). The server applies it to
     * each raw element of `PushRequest.ops` as parsed, **before** decoding or validating it, so an
     * unsupported or invalid op is measured too and the result does not depend on the sender's
     * whitespace or member order. May be computed without building the string.
     */
    public fun sizeOf(value: JsonValue): Int

    /** Number of bytes of [text] in UTF-8 (a surrogate pair counts 4). */
    public fun utf8Size(text: String): Int
}
