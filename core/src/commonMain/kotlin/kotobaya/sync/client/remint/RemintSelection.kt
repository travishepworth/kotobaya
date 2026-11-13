package kotobaya.sync.client.remint

import kotobaya.contracts.Uuid
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.rejected.OpFacts
import kotobaya.sync.client.rejected.RejectionCodes
import kotobaya.sync.client.rejected.rejectionCode
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState

/** One op of `E` (SYNC.md 10.4 step 1) with what step 6a needs. */
public data class RemintEntry(
    /** The original `pending_op` row. */
    public val row: PendingOpRow,
    /** The row decoded. */
    public val op: Op,
    /** The owner note (`note.created` in `E`) of a window-derived op (step 1c), else null. */
    public val owner: Op?,
)

/** One op minted by step 6a: payload as minted (re-keyed where 10.4 says so) for one entry of `E`. */
public data class PlannedMint(
    /** The entry of `E` it comes from. */
    public val entry: RemintEntry,
    /** The op type (same as the original's). */
    public val type: OpType,
    /** The payload to mint (the original's, or with `item_key` re-keyed `n:<owner>`). */
    public val payload: JsonObject,
)

/**
 * The selection of SYNC.md 10.4 step 1 (1a, 1b, 1c, order of `E`, the extra `U` rows), computed
 * from the outbox alone. Pure: no store access. Lists hold rows in the order the contract defines.
 */
public data class RemintSelection(
    /** `W`: ids of the rows created in the window. */
    public val window: Set<Uuid>,
    /** `C`: every clock-rejected row, ascending original hlc. */
    public val clock: List<PendingOpRow>,
    /** `D`: rows that depend on a window row but write a row other replicas can have (discarded). */
    public val dependents: List<PendingOpRow>,
    /** `E` in the order of step 1 (creations, window-row writes, window-derived ops). */
    public val remint: List<RemintEntry>,
    /** `R`: reviews to re-mint (before step 4 drops the undone ones), ascending original hlc. */
    public val reviews: List<PendingOpRow>,
    /** `U`: undos of reviews in `R` (any state), discarded. */
    public val undos: List<PendingOpRow>,
) {
    /** Op ids of `C ∪ D ∪ E ∪ R ∪ U`: every row step 2 deletes. */
    public val deleted: Set<Uuid> by lazy {
        (clock + dependents + reviews + undos).mapTo(LinkedHashSet()) { it.opId }.also { s -> remint.forEach { s += it.row.opId } }
    }

    /** True iff some row of `C` is `clock_ahead` (step 2: clock repair). */
    public val needsClockRepair: Boolean get() = clock.any { it.rejectionCode == RejectionCodes.CLOCK_AHEAD }

    /**
     * Step 6a payloads, in `E` order: a window-derived `deck.item_put e:<seq>` into a deck not in
     * `W` is re-keyed `n:<owner>` iff the owner is live (no `note.deleted` of it in `E`); a
     * window-derived `deck.item_removed e:<seq>` whose `(deck, e:<seq>)` put an earlier entry of `E`
     * re-keyed is minted twice (`n:<owner>`, then unchanged); everything else once, unchanged.
     */
    public fun planned(): List<List<PlannedMint>> {
        val deletedNotes = remint.filter { it.op.opType == OpType.NOTE_DELETED }.mapNotNullTo(HashSet()) { it.op.str("id") }
        val rekeyed = HashSet<Pair<String, String>>()
        return remint.map { e ->
            val op = e.op
            val owner = e.owner
            val deck = op.str("deck_id")
            val key = op.str("item_key")
            val ownerId = owner?.str("id")
            val derivedItem = ownerId != null && deck != null && key != null && key.startsWith("e:") && deck !in window
            when {
                derivedItem && op.opType == OpType.DECK_ITEM_PUT && ownerId !in deletedNotes -> {
                    rekeyed += deck to key
                    listOf(PlannedMint(e, op.opType, op.payload.with("item_key", "n:$ownerId")))
                }
                derivedItem && op.opType == OpType.DECK_ITEM_REMOVED && (deck to key) in rekeyed -> listOf(
                    PlannedMint(e, op.opType, op.payload.with("item_key", "n:$ownerId")),
                    PlannedMint(e, op.opType, op.payload),
                )
                else -> listOf(PlannedMint(e, op.opType, op.payload))
            }
        }
    }

    /** Statics. */
    public companion object {
        /**
         * Step 1 over [rows] (every `pending_op` row, any state; only `rejected` ones are selected,
         * the others are searched for undos of `R` reviews). Rows that do not decode are never selected.
         */
        public fun of(rows: List<PendingOpRow>): RemintSelection {
            val rejected = rows.filter { it.state == PendingState.REJECTED }.sortedBy { it.hlc }
            val window = LinkedHashSet<Uuid>()
            val reviewIds = HashSet<Uuid>()
            val c = ArrayList<PendingOpRow>()
            val d = ArrayList<PendingOpRow>()
            val e = ArrayList<RemintEntry>()
            val r = ArrayList<PendingOpRow>()
            val u = ArrayList<PendingOpRow>()
            val inER = HashSet<Uuid>()
            for (row in rejected) {
                val code = row.rejectionCode
                val op = OpFacts.decode(row) ?: continue
                if (RejectionCodes.isClock(code)) {
                    // 1a
                    c += row
                    when {
                        op.opType == OpType.REVIEW_LOGGED -> {
                            r += row
                            op.str("id")?.let { reviewIds += it }
                            inER += row.opId
                        }
                        OpFacts.isCreation(op) -> {
                            e += RemintEntry(row, op, null)
                            OpFacts.createdId(op)?.let { window += it }
                            inER += row.opId
                        }
                        OpFacts.writesWindowRow(op, window) -> {
                            e += RemintEntry(row, op, null)
                            inER += row.opId
                        }
                    }
                } else if (code == RejectionCodes.UNKNOWN_REFERENCE && OpFacts.refs(op).any { it in window || it in reviewIds }) {
                    // 1b
                    when {
                        op.opType == OpType.REVIEW_LOGGED -> {
                            r += row
                            op.str("id")?.let { reviewIds += it }
                            inER += row.opId
                        }
                        op.opType == OpType.REVIEW_UNDONE -> u += row
                        OpFacts.isCreation(op) || OpFacts.writesWindowRow(op, window) -> {
                            e += RemintEntry(row, op, null)
                            OpFacts.createdId(op)?.let { window += it }
                            inER += row.opId
                        }
                        else -> d += row
                    }
                }
            }
            // 1c: window-derived ops of C; owner = the first note.created in E (hlc order) with the entry
            val noteByEntry = LinkedHashMap<Long, Op>()
            for (x in e) {
                if (x.op.opType != OpType.NOTE_CREATED) continue
                val seq = x.op.long("entry_seq") ?: continue
                if (seq !in noteByEntry) noteByEntry[seq] = x.op
            }
            for (row in c) {
                if (row.opId in inER) continue
                val op = OpFacts.decode(row) ?: continue
                val seq = derivedSeq(op) ?: continue
                val owner = noteByEntry[seq] ?: continue
                e += RemintEntry(row, op, owner)
                inER += row.opId
            }
            e.sortWith(
                compareBy<RemintEntry>(
                    { maxOf(it.row.hlc, it.owner?.hlc ?: it.row.hlc) },
                    { it.owner != null && it.row.hlc < it.owner.hlc },
                    { it.row.hlc },
                ),
            )
            // every other review.undone row (any state) of a review in R
            val inU = u.mapTo(HashSet()) { it.opId }
            for (row in rows.sortedBy { it.hlc }) {
                if (row.opType != OpType.REVIEW_UNDONE.wire || row.opId in inU) continue
                val op = OpFacts.decode(row) ?: continue
                if (op.str("review_id") in reviewIds) {
                    u += row
                    inU += row.opId
                }
            }
            return RemintSelection(window, c, d, e, r, u)
        }

        /** The entry of a window-derived candidate: `e:<seq>` of a deck item, `entry_seq` of a word status. */
        private fun derivedSeq(op: Op): Long? = when (op.opType) {
            OpType.DECK_ITEM_PUT, OpType.DECK_ITEM_REMOVED ->
                op.str("item_key")?.takeIf { it.startsWith("e:") }?.substring(2)?.toLongOrNull()
            OpType.WORD_STATUS_SET, OpType.WORD_STATUS_CLEARED -> op.long("entry_seq")
            else -> null
        }
    }
}

internal fun Op.str(key: String): String? = (payload.members[key] as? JsonString)?.value

internal fun Op.long(key: String): Long? = (payload.members[key] as? JsonNumber)?.value?.toLong()

internal fun JsonObject.with(key: String, value: String): JsonObject =
    JsonObject(LinkedHashMap(members).also { it[key] = JsonString(value) })
