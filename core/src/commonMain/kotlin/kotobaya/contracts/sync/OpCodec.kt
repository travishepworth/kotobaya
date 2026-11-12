package kotobaya.contracts.sync

import kotobaya.contracts.HlcString
import kotobaya.contracts.Uuid

/** Who may mint an op type (`ops/index.json` `emitter`). */
public enum class Emitter {
    /** Any replica (phone, web, script, and the server on behalf of a REST call). */
    CLIENT,

    /** Only the server; a pushed one is rejected `server_only`. */
    SERVER,
}

/**
 * The closed set of v1 op types, one entry per row of `contracts/sync/ops/index.json`, in the same
 * order. `contracts/core/check.sh` fails if this enum and the registry disagree.
 */
public enum class OpType(
    /** Dotted wire name, e.g. `note.created`. */
    public val wire: String,
    /** `entity_type` (target table). */
    public val entityType: String,
    /**
     * Payload properties whose values, as text, joined with `|`, form `entity_id`. A part written
     * in single quotes (`'manual'`) is a literal and contributes the text between the quotes.
     */
    public val entityIdParts: List<String>,
    /** Who may mint it. */
    public val emitter: Emitter,
    /** `false` = a device advances its cursor past the op without touching any table (SYNC.md 6.1). */
    public val deviceApplies: Boolean,
) {
    /** `note_type.created` (OPS.md). */
    NOTE_TYPE_CREATED("note_type.created", "note_type", listOf("id"), Emitter.CLIENT, true),

    /** `note_type.updated` (OPS.md). */
    NOTE_TYPE_UPDATED("note_type.updated", "note_type", listOf("id"), Emitter.CLIENT, true),

    /** `note_type.deleted` (OPS.md). */
    NOTE_TYPE_DELETED("note_type.deleted", "note_type", listOf("id"), Emitter.CLIENT, true),

    /** `note.created` (OPS.md). */
    NOTE_CREATED("note.created", "note", listOf("id"), Emitter.CLIENT, true),

    /** `note.updated` (OPS.md). */
    NOTE_UPDATED("note.updated", "note", listOf("id"), Emitter.CLIENT, true),

    /** `note.deleted` (OPS.md). */
    NOTE_DELETED("note.deleted", "note", listOf("id"), Emitter.CLIENT, true),

    /** `note.merged` (OPS.md). */
    NOTE_MERGED("note.merged", "note", listOf("loser_id"), Emitter.SERVER, true),

    /** `card.created` (OPS.md). */
    CARD_CREATED("card.created", "card", listOf("id"), Emitter.CLIENT, true),

    /** `card.suspended` (OPS.md). */
    CARD_SUSPENDED("card.suspended", "card", listOf("id"), Emitter.CLIENT, true),

    /** `card.unsuspended` (OPS.md). */
    CARD_UNSUSPENDED("card.unsuspended", "card", listOf("id"), Emitter.CLIENT, true),

    /** `card.unburied` (OPS.md). */
    CARD_UNBURIED("card.unburied", "card", listOf("id"), Emitter.CLIENT, true),

    /** `card.deleted` (OPS.md). */
    CARD_DELETED("card.deleted", "card", listOf("id"), Emitter.CLIENT, true),

    /** `card.buried` (OPS.md). */
    CARD_BURIED("card.buried", "card", listOf("id"), Emitter.CLIENT, true),

    /** `card.flag_set` (OPS.md). */
    CARD_FLAG_SET("card.flag_set", "card", listOf("id"), Emitter.CLIENT, true),

    /** `card.preset_set` (OPS.md). */
    CARD_PRESET_SET("card.preset_set", "card", listOf("id"), Emitter.CLIENT, true),

    /** `card.merged` (OPS.md). */
    CARD_MERGED("card.merged", "card", listOf("loser_id"), Emitter.SERVER, true),

    /** `review.logged` (OPS.md). */
    REVIEW_LOGGED("review.logged", "review_log", listOf("id"), Emitter.CLIENT, true),

    /** `review.undone` (OPS.md). */
    REVIEW_UNDONE("review.undone", "review_log", listOf("review_id"), Emitter.CLIENT, true),

    /** `word.status_set` (OPS.md). */
    WORD_STATUS_SET("word.status_set", "word_status", listOf("entry_seq"), Emitter.CLIENT, true),

    /** `word.status_cleared` (OPS.md). */
    WORD_STATUS_CLEARED("word.status_cleared", "word_status", listOf("entry_seq"), Emitter.CLIENT, true),

    /** `deck.created` (OPS.md). */
    DECK_CREATED("deck.created", "deck", listOf("id"), Emitter.CLIENT, true),

    /** `deck.updated` (OPS.md). */
    DECK_UPDATED("deck.updated", "deck", listOf("id"), Emitter.CLIENT, true),

    /** `deck.deleted` (OPS.md). */
    DECK_DELETED("deck.deleted", "deck", listOf("id"), Emitter.CLIENT, true),

    /** `deck.content_published` (OPS.md). */
    DECK_CONTENT_PUBLISHED("deck.content_published", "deck", listOf("deck_id"), Emitter.SERVER, true),

    /** `deck.item_put` (OPS.md). */
    DECK_ITEM_PUT("deck.item_put", "deck_item", listOf("deck_id", "item_key"), Emitter.CLIENT, true),

    /** `deck.item_removed` (OPS.md). */
    DECK_ITEM_REMOVED("deck.item_removed", "deck_item", listOf("deck_id", "item_key"), Emitter.CLIENT, true),

    /** `confusable.suggested` (OPS.md). */
    CONFUSABLE_SUGGESTED("confusable.suggested", "confusable", listOf("note_a_id", "note_b_id", "reason"), Emitter.SERVER, true),

    /** `confusable.confirmed` (OPS.md). */
    CONFUSABLE_CONFIRMED("confusable.confirmed", "confusable", listOf("note_a_id", "note_b_id", "reason"), Emitter.CLIENT, true),

    /** `confusable.rejected` (OPS.md). */
    CONFUSABLE_REJECTED("confusable.rejected", "confusable", listOf("note_a_id", "note_b_id", "reason"), Emitter.CLIENT, true),

    /** `grammar.created` (OPS.md). */
    GRAMMAR_CREATED("grammar.created", "grammar_point", listOf("id"), Emitter.CLIENT, true),

    /** `grammar.updated` (OPS.md). */
    GRAMMAR_UPDATED("grammar.updated", "grammar_point", listOf("id"), Emitter.CLIENT, true),

    /** `grammar.deleted` (OPS.md). */
    GRAMMAR_DELETED("grammar.deleted", "grammar_point", listOf("id"), Emitter.CLIENT, true),

    /** `grammar.span_put` (OPS.md). */
    GRAMMAR_SPAN_PUT("grammar.span_put", "sentence_grammar_span", listOf("sentence_id", "grammar_point_id", "start_offset", "end_offset", "'manual'"), Emitter.CLIENT, true),

    /** `grammar.span_removed` (OPS.md). */
    GRAMMAR_SPAN_REMOVED("grammar.span_removed", "sentence_grammar_span", listOf("sentence_id", "grammar_point_id", "start_offset", "end_offset", "'manual'"), Emitter.CLIENT, true),

    /** `settings.set` (OPS.md). */
    SETTINGS_SET("settings.set", "settings", listOf("key"), Emitter.CLIENT, true),

    /** `fsrs_params.created` (OPS.md). */
    FSRS_PARAMS_CREATED("fsrs_params.created", "fsrs_params", listOf("id"), Emitter.CLIENT, true),

    /** `fsrs_params.updated` (OPS.md). */
    FSRS_PARAMS_UPDATED("fsrs_params.updated", "fsrs_params", listOf("id"), Emitter.CLIENT, true),

    /** `fsrs_params.deleted` (OPS.md). */
    FSRS_PARAMS_DELETED("fsrs_params.deleted", "fsrs_params", listOf("id"), Emitter.CLIENT, true),

    /** `fsrs_params.published` (OPS.md). */
    FSRS_PARAMS_PUBLISHED("fsrs_params.published", "fsrs_params", listOf("id"), Emitter.SERVER, true),

    /** `media.created` (OPS.md). */
    MEDIA_CREATED("media.created", "media", listOf("id"), Emitter.SERVER, true),

    /** `media.updated` (OPS.md). */
    MEDIA_UPDATED("media.updated", "media", listOf("id"), Emitter.CLIENT, true),

    /** `media.deleted` (OPS.md). */
    MEDIA_DELETED("media.deleted", "media", listOf("id"), Emitter.CLIENT, true),

    /** `media.content_published` (OPS.md). */
    MEDIA_CONTENT_PUBLISHED("media.content_published", "media", listOf("media_id"), Emitter.SERVER, true),

    /** `parse_override.put` (OPS.md). */
    PARSE_OVERRIDE_PUT("parse_override.put", "parse_override", listOf("id"), Emitter.CLIENT, false),

    /** `parse_override.deleted` (OPS.md). */
    PARSE_OVERRIDE_DELETED("parse_override.deleted", "parse_override", listOf("id"), Emitter.CLIENT, false)
    ;

    /** Constants. */
    public companion object {
        /** The highest `op_version` this core can decode, for every op type. Always 1 in v1. */
        public const val MAX_SUPPORTED_VERSION: Int = 1
    }
}

/** Where an op came from; decides which optional envelope properties must be present. */
public enum class OpSource {
    /** Pushed by a client (`PushRequest.ops`, a device's `pending_op`): `seq` and `caused_by_op_id` MUST be absent. */
    PUSHED,

    /** Pulled from the log (`PullResponse.ops`): `seq` (≥ 1) and `caused_by_op_id` (uuid or null) MUST be present. */
    PULLED,
}

/**
 * One op (SYNC.md 2), decoded. Property names follow the envelope; see `op.schema.json` for each
 * definition.
 *
 * Invariant for an op produced by [OpCodec.decode] with [OpDecodeResult.Known]: the envelope and
 * payload validate against `op.schema.json` and `ops/<op_type>.schema.json`. Consistency rules
 * outside JSON Schema (entity id derivation, device id = device part of hlc) are checked separately
 * by [OpCodec.envelopeErrors].
 */
public data class Op(
    /** `op_id`: UUIDv7, the idempotency key. */
    public val opId: Uuid,
    /** `op_type`. */
    public val opType: OpType,
    /** `op_version`; `1` for every v1 op. */
    public val opVersion: Int,
    /** `entity_type`; equals [OpType.entityType] for a valid op. */
    public val entityType: String,
    /** `entity_id`; equals [OpCodec.entityIdOf] of the payload for a valid op. */
    public val entityId: String,
    /** `device_id`; equals the device part of [hlc] for a valid op. */
    public val deviceId: Uuid,
    /** `hlc`. */
    public val hlc: HlcString,
    /** `payload`, as received/minted. Member order is kept and is the order [OpCodec.encode] writes. */
    public val payload: JsonObject,
    /** `seq`: non-null iff the op was pulled ([OpSource.PULLED]). */
    public val seq: Long? = null,
    /** `caused_by_op_id`: only meaningful for pulled ops; null = not server-emitted as a consequence. */
    public val causedByOpId: Uuid? = null,
)

/** Result of [OpCodec.decode]. */
public sealed interface OpDecodeResult {
    /** A supported op type and version that validates against its schemas. */
    public data class Known(
        /** The decoded op. */
        public val op: Op,
    ) : OpDecodeResult

    /**
     * The lenient path (SYNC.md 6.1, `batch.schema.json#/$defs/PullResponseLenient`): the envelope
     * has the lenient shape (`op_type` matches `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$`,
     * `op_version` is an integer ≥ 1, the other envelope properties have their types), but the
     * `op_type` is not one of [OpType] or `op_version > OpType.MAX_SUPPORTED_VERSION`. The payload is
     * not validated. A device applies the ops before it, sets `pull_seq` to the `seq` of the op
     * before it, and stops in *upgrade required*; it never skips the op. The server
     * answers such a pushed op `unsupported_op`.
     */
    public data class Unsupported(
        /** The `op_type` as received. */
        public val opType: String,
        /** The `op_version` as received. */
        public val opVersion: Int,
        /** `seq` if present (pulled ops), else null. */
        public val seq: Long?,
        /** The whole envelope as received, for diagnostics and for storing unchanged. */
        public val raw: JsonObject,
    ) : OpDecodeResult

    /**
     * Not a valid op: not JSON, not an object, the lenient envelope shape fails, or a known
     * type/version fails its schemas (including `additionalProperties: false`, `allOf` conditionals,
     * `seq` presence per [OpSource]). The server answers `schema_invalid`. A device that meets one in
     * a pull treats it like [Unsupported] (stops before it, never skips it) and reports it as a
     * diagnostic, because the log is never wrong by construction.
     */
    public data class Invalid(
        /** English diagnostic naming the failing rule; for logs and `error.message` only. */
        public val message: String,
        /** JSON Pointer (RFC 6901) to the failing value, e.g. `/payload/rating`; `""` for the whole op. */
        public val pointer: String,
        /** `seq` if it could be read, else null. */
        public val seq: Long?,
    ) : OpDecodeResult
}

/** A consistency rule of SYNC.md 2 / 5.3 `schema_invalid` that JSON Schema cannot express. */
public enum class EnvelopeError {
    /** `entity_type` differs from [OpType.entityType]. */
    ENTITY_TYPE_MISMATCH,

    /** `entity_id` differs from [OpCodec.entityIdOf] of the payload. */
    ENTITY_ID_MISMATCH,

    /** `device_id` differs from the last 36 characters of `hlc`. */
    DEVICE_ID_MISMATCH,

    /** `seq` / `caused_by_op_id` present on a pushed op, or `seq` absent on a pulled op. */
    SEQ_PRESENCE,
}

/**
 * Encodes and decodes ops per `contracts/sync` (op.schema.json, ops/\*.schema.json,
 * common.schema.json) using [CanonicalJson].
 *
 * Round trip: for every op `o` with `decode(encode(o), src) == Known(o2)`, `o2 == o`.
 * Determinism: [encode] of equal ops gives identical strings on every platform.
 */
public interface OpCodec {
    /**
     * Canonical compact JSON of the envelope. Member order: `op_id`, `op_type`, `op_version`,
     * `entity_type`, `entity_id`, `device_id`, `hlc`, `payload` (members in [Op.payload] order),
     * then — only if [Op.seq] is non-null — `seq` and `caused_by_op_id` (JSON `null` when
     * [Op.causedByOpId] is null). No other members. This exact string is what a client pushes.
     */
    public fun encode(op: Op): String

    /** [encode] as a [JsonObject] (same members, same order), for embedding into an HTTP body built as a tree. */
    public fun encodeTree(op: Op): JsonObject

    /**
     * Decodes one envelope from JSON text. Order of checks: JSON syntax → lenient envelope shape →
     * known type and supported version (else [OpDecodeResult.Unsupported]) → full schema validation
     * (else [OpDecodeResult.Invalid]) → [OpDecodeResult.Known]. Never throws.
     *
     * Number handling: integer-valued properties (`op_version`, `seq`, instants, `entry_seq`, …)
     * accept any JSON number that is integral (`3` and `3.0` both decode to 3) and fail on a
     * fraction. The returned payload holds [JsonNumber]s as decoded; re-encoding normalises
     * `3.0` to `3`.
     */
    public fun decode(json: String, source: OpSource): OpDecodeResult

    /** [decode] for an already-parsed tree (one element of `PullResponse.ops`). */
    public fun decodeTree(json: JsonValue, source: OpSource): OpDecodeResult

    /**
     * `entity_id` derived from [payload] for [type] (`ops/index.json`): each part of
     * [OpType.entityIdParts] as text — a string as is, an integer as decimal digits, a quoted
     * literal without its quotes — joined with `|`.
     *
     * Example: `grammar.span_put` with `sentence_id = s`, `grammar_point_id = g`,
     * `start_offset = 3`, `end_offset = 5` → `"s|g|3|5|manual"`.
     *
     * @throws IllegalArgumentException if a required part is missing or not a string/integer.
     */
    public fun entityIdOf(type: OpType, payload: JsonObject): String

    /**
     * The consistency rules outside JSON Schema, for an op that decoded as [OpDecodeResult.Known]
     * from [source]. Empty list = consistent. The server rejects a pushed op with any error as
     * `schema_invalid`; a device never checks pulled ops.
     */
    public fun envelopeErrors(op: Op, source: OpSource): List<EnvelopeError>

    /**
     * The op's size of SYNC.md 5.4: `utf8Size(encode(op))`. A client MUST refuse to mint an op with
     * `sizeBytes(op) > maxOpBytes` (SYNC.md 4); the server rejects such a pushed op `op_too_large`
     * (measuring the canonical re-encoding of what it decoded, so whitespace a client added does not
     * count). An op of exactly `maxOpBytes` is allowed.
     */
    public fun sizeBytes(op: Op): Int
}
