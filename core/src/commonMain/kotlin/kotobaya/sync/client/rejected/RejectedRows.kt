package kotobaya.sync.client.rejected

import kotobaya.contracts.Reserved
import kotobaya.contracts.Uuid
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.toOp

/** Rejection codes the device acts on (SYNC.md 5.3); every other code is a permanent rejection like these. */
public object RejectionCodes {
    /** `clock_ahead` (SYNC.md 3.4). */
    public const val CLOCK_AHEAD: String = "clock_ahead"

    /** `clock_behind` (SYNC.md 3.4). */
    public const val CLOCK_BEHIND: String = "clock_behind"

    /** `unknown_reference` (SYNC.md 5.3). */
    public const val UNKNOWN_REFERENCE: String = "unknown_reference"

    /** True for the two clock codes, which Re-mint handles and Retry is never offered for (SYNC.md 10.2). */
    public fun isClock(code: String?): Boolean = code == CLOCK_AHEAD || code == CLOCK_BEHIND
}

/** The code of a rejected row: the part of `last_error` before the first `:` (SYNC.md 5.4 `"<code>: <message>"`), or null. */
public val PendingOpRow.rejectionCode: String?
    get() = lastError?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() }

/** The message part of `last_error` (after `"<code>: "`), or null. Shown, never parsed (SYNC.md 5.3). */
public val PendingOpRow.rejectionMessage: String?
    get() = lastError?.let { e -> if (':' in e) e.substringAfter(':').trim() else null }

/**
 * Op-level facts the rejected-op actions need (SYNC.md 10.4): `refs(op)`, whether the op creates a
 * row, which row ids its `entity_id` names.
 */
internal object OpFacts {
    /** The row as an [Op], or null when it does not decode (a corrupt row: left untouched and listed). */
    fun decode(row: PendingOpRow): Op? = try {
        row.toOp()
    } catch (e: IllegalStateException) {
        null
    }

    private fun Op.str(key: String): String? = (payload.members[key] as? JsonString)?.value

    /**
     * `refs(op)` of SYNC.md 10.4 step 1: the ids of the rows whose absence makes the op
     * `unknown_reference` (OPS.md "Server rejects").
     */
    fun refs(op: Op): List<String> = buildList {
        fun key(k: String) {
            op.str(k)?.let { add(it) }
        }
        when (op.opType) {
            OpType.NOTE_CREATED -> {
                key("note_type_id")
                key("grammar_point_id")
            }
            OpType.CARD_CREATED -> {
                key("note_id")
                add(op.str("preset_id") ?: Reserved.DEFAULT_PRESET_ID)
            }
            OpType.CARD_PRESET_SET -> {
                key("id")
                key("preset_id")
            }
            OpType.REVIEW_LOGGED -> key("card_id")
            OpType.REVIEW_UNDONE -> key("review_id")
            OpType.DECK_ITEM_PUT, OpType.DECK_ITEM_REMOVED -> {
                key("deck_id")
                op.str("item_key")?.takeIf { it.startsWith("n:") }?.let { add(it.substring(2)) }
            }
            OpType.CONFUSABLE_CONFIRMED, OpType.CONFUSABLE_REJECTED, OpType.CONFUSABLE_SUGGESTED -> {
                key("note_a_id")
                key("note_b_id")
            }
            OpType.GRAMMAR_SPAN_PUT, OpType.GRAMMAR_SPAN_REMOVED -> key("grammar_point_id")
            OpType.NOTE_MERGED, OpType.CARD_MERGED -> {
                key("loser_id")
                key("survivor_id")
            }
            OpType.DECK_CONTENT_PUBLISHED -> key("deck_id")
            OpType.MEDIA_CONTENT_PUBLISHED -> key("media_id")
            OpType.WORD_STATUS_SET, OpType.WORD_STATUS_CLEARED, OpType.SETTINGS_SET,
            OpType.PARSE_OVERRIDE_PUT, OpType.PARSE_OVERRIDE_DELETED,
            -> Unit
            else -> if (!isCreation(op)) key("id")
        }
    }

    /** A `*.created` op (the client-emitted creations of SYNC.md 10.4 plus `media.created`). */
    fun isCreation(op: Op): Boolean = op.opType.wire.endsWith(".created")

    /** The id of the row a creation creates (`payload.id`). */
    fun createdId(op: Op): Uuid? = if (isCreation(op)) op.str("id") else null

    /**
     * The row ids named by `entity_id` (SYNC.md 10.4 "Window row"): its `|`-separated parts, a deck
     * item key `n:<id>` counting as `<id>`.
     */
    fun entityIds(op: Op): List<String> = op.entityId.split('|').map { if (it.startsWith("n:")) it.substring(2) else it }

    /** True iff the row [op] writes is a window row: one part of its identity is in [window]. */
    fun writesWindowRow(op: Op, window: Set<String>): Boolean = entityIds(op).any { it in window }
}
