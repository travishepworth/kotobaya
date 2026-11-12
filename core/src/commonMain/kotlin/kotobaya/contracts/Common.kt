/**
 * Frozen interfaces of the shared Kotlin core (T006). Interfaces and data holders only: the
 * implementations live in other packages of this module and are written by T107 (FSRS),
 * T108 (search), T202 (sync), T203 (replay), T205 (queue) and T213 (renderer).
 *
 * Map of this package tree: `contracts/core/README.md`. Every rule written in a KDoc here is
 * normative; if an implementation needs to deviate, it returns `CONTRACT_CHANGE_REQUEST`.
 */
package kotobaya.contracts

/**
 * A canonical lowercase UUID in `8-4-4-4-12` form (`contracts/db/SCHEMA.md` 2.1), for example
 * `0193a1b2-0001-7000-8000-000000000001`. Comparisons between ids are byte-wise string
 * comparisons (lowercase text order equals UUID byte order).
 */
public typealias Uuid = String

/**
 * A hybrid logical clock value, 54 characters:
 * `<12 hex ms>-<4 hex counter>-<36-char device uuid>`, for example
 * `0193a1b2c3d4-0001-01931f3e-0000-7000-8000-0000000000a1` (SYNC.md 3.1).
 * Ordering is byte-wise string ordering (`String.compareTo`), which is clock order.
 */
public typealias HlcString = String

/**
 * An instant as integer milliseconds since the Unix epoch, UTC (`common.schema.json#/$defs/instant_ms`).
 * Range `0 ..= 2^48 - 1`. All instants in these interfaces are UTC; a time zone only ever enters
 * through an explicit UTC offset or a [kotobaya.contracts.queue.StudyDay].
 */
public typealias InstantMs = Long

/** Ids and constants that are identical on every replica (`contracts/db/SCHEMA.md` 2.1, SYNC.md 3.4). */
public object Reserved {
    /** Device id of the server; origin of server-emitted ops. */
    public const val SERVER_DEVICE_ID: Uuid = "00000000-0000-7000-8000-000000000001"

    /** Id of the built-in Default FSRS preset (`fsrs_params`). Never tombstoned. */
    public const val DEFAULT_PRESET_ID: Uuid = "00000000-0000-7000-8000-000000000010"

    /** Built-in note type `vocab`. */
    public const val NOTE_TYPE_VOCAB: Uuid = "00000000-0000-7000-8000-000000000101"

    /** Built-in note type `kanji`. */
    public const val NOTE_TYPE_KANJI: Uuid = "00000000-0000-7000-8000-000000000102"

    /** Built-in note type `phrase`. */
    public const val NOTE_TYPE_PHRASE: Uuid = "00000000-0000-7000-8000-000000000103"

    /** Built-in note type `grammar`. */
    public const val NOTE_TYPE_GRAMMAR: Uuid = "00000000-0000-7000-8000-000000000104"

    /** Built-in note type `sentence`. */
    public const val NOTE_TYPE_SENTENCE: Uuid = "00000000-0000-7000-8000-000000000105"

    /** Built-in note type `custom`. */
    public const val NOTE_TYPE_CUSTOM: Uuid = "00000000-0000-7000-8000-000000000106"

    /** `FLOOR` of SYNC.md 3.4: 2020-01-01T00:00:00Z. No accepted HLC wall time or `reviewed_at` is below it. */
    public const val CLOCK_FLOOR_MS: InstantMs = 1_577_836_800_000L

    /** The 24 h clock tolerance of SYNC.md 3.4, in ms. */
    public const val CLOCK_TOLERANCE_MS: Long = 86_400_000L

    /** The ε of SYNC.md 3.4 (60 s), applied to the server's HLC check only. */
    public const val CLOCK_EPSILON_MS: Long = 60_000L

    /** Default `max_op_bytes` (1 MiB) used before `GET /meta` has been read (SYNC.md 5.4). */
    public const val DEFAULT_MAX_OP_BYTES: Int = 1_048_576

    /** One day in milliseconds. Interval arithmetic in the core is always in exact 86 400 000 ms days. */
    public const val DAY_MS: Long = 86_400_000L

    /**
     * `card.last_review_id` of a **refused cache** (SYNC.md 9.4, C-8): the nil UUID, never a
     * `review_log.id`. Written by the caller when [kotobaya.contracts.replay.ReplayEngine.replay] or
     * `fold` throws; makes `ReplayEngine.decide` answer FULL_REPLAY for every later row.
     */
    public const val REPLAY_REFUSED_ID: Uuid = "00000000-0000-0000-0000-000000000000"
}

/** The six note base types (`note_type.base_type`, `note.type`). */
public enum class NoteBaseType(
    /** Value stored in the database and used on the wire. */
    public val wire: String,
) {
    /** A word; may carry `entry_seq`. */
    VOCAB("vocab"),

    /** A kanji; carries `kanji_literal`. */
    KANJI("kanji"),

    /** A free-form phrase without a dictionary entry. */
    PHRASE("phrase"),

    /** A grammar point; carries `grammar_point_id`. */
    GRAMMAR("grammar"),

    /** A sentence. */
    SENTENCE("sentence"),

    /** A user-defined type. */
    CUSTOM("custom"),
}

/** Word status values (`word_status.status`, SCHEMA.md 4.2 "Word status"). Absent row = [NEW]. */
public enum class WordStatus(
    /** Value stored in the database and used on the wire. */
    public val wire: String,
) {
    /** Not yet studied (also: no row). */
    NEW("new"),

    /** Being studied (auto status). */
    LEARNING("learning"),

    /** Known: auto status of a mature word, or a user/import override. */
    KNOWN("known"),

    /** Override: known forever, never reviewed. */
    NEVER_FORGET("never_forget"),

    /** Override: ignored everywhere (queue, coverage, suggestions). */
    BLACKLISTED("blacklisted"),

    /** Override: ignored (e.g. jpdb "ignored"); treated like [BLACKLISTED] by the queue. */
    IGNORED("ignored"),
}
