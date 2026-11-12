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
 * double represents all of them exactly. A decoder MUST report an integer literal with magnitude
 * `>= 2^53` as invalid rather than round it.
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
 * A JSON object. [members] keeps insertion order (use `LinkedHashMap` / `mapOf`, which preserve it
 * on every Kotlin platform); [CanonicalJson.encode] writes members in that order. A decoder
 * rejects an object with a duplicate key.
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
 * `utf8Size(encode(op))`, so they always agree.
 *
 * Encoding rules (all normative):
 * - No whitespace anywhere outside strings.
 * - Object members in [JsonObject.members] order; arrays in order.
 * - Strings: `"` → `\"`, `\` → `\\`, U+0008 → `\b`, U+000C → `\f`, U+000A → `\n`, U+000D → `\r`,
 *   U+0009 → `\t`, other code points below U+0020 → `\u00XX` with lowercase hex. Every other code
 *   point, including `/`, U+007F and all non-ASCII text, is written **raw** (UTF-8 in bytes), never
 *   `\u`-escaped. A string with an unpaired surrogate cannot be encoded (IllegalArgumentException).
 * - Numbers: if the value is integral and its magnitude is `< 2^53`, its decimal digits with an
 *   optional leading `-` and no fraction or exponent (`1`, not `1.0`; `-0.0` → `0`). Otherwise the
 *   ECMAScript `Number::toString` form (shortest round-trip digits; exponent form `1e+21` / `1e-7`
 *   exactly as JavaScript prints it). This is what `JSON.stringify` produces; JVM code MUST NOT use
 *   `Double.toString`, which differs.
 * - `true`, `false`, `null` as literals.
 *
 * Example: `{"a":[1,0.9,"食べる\n"],"b":null}` is canonical; `{"a": [1.0]}` is not (its canonical
 * form is `{"a":[1]}`).
 */
public interface CanonicalJson {
    /**
     * Parses any RFC 8259 JSON text (whitespace allowed, any escapes). Rejects: trailing content
     * after the value, duplicate object keys, unpaired surrogates (raw or `\u`-escaped), integer
     * literals with magnitude `>= 2^53`, numbers that overflow a double. Never throws.
     */
    public fun decode(text: String): JsonDecodeResult

    /** Encodes [value] canonically (rules above). `decode(encode(v)).value == v` for every encodable `v`. */
    public fun encode(value: JsonValue): String

    /** Number of bytes of [text] in UTF-8 (a surrogate pair counts 4). */
    public fun utf8Size(text: String): Int
}
