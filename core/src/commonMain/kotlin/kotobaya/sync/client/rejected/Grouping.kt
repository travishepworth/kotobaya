package kotobaya.sync.client.rejected

import kotobaya.contracts.Uuid
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.store.PendingOpRow

/**
 * Groups rejected rows under their rejected parent (SYNC.md 10.2 "the rejection list SHOULD group
 * children under their rejected parent"; 10.4 "grouped under their window parent"): an op's parent
 * is the rejected creation (or review, for an undo) of a row it references; the group is the root of
 * that chain. Rows are visited in ascending hlc, so parents are always seen first (SYNC.md 4).
 */
internal class ParentIndex(rows: List<PendingOpRow>) {
    private val rootOf = HashMap<Uuid, Uuid?>()

    init {
        val creatorOfRow = HashMap<String, Uuid>()
        for (row in rows.sortedBy { it.hlc }) {
            val op = OpFacts.decode(row)
            if (op == null) {
                rootOf[row.opId] = null
                continue
            }
            val parent = OpFacts.refs(op).firstNotNullOfOrNull { creatorOfRow[it] }
            rootOf[row.opId] = parent?.let { rootOf[it] ?: it }
            createdRow(op)?.let { if (it !in creatorOfRow) creatorOfRow[it] = row.opId }
        }
    }

    /** The op id of the root of [opId]'s chain, or null when the row is a root itself (or unknown). */
    fun parentOf(opId: Uuid): Uuid? = rootOf[opId]

    private fun createdRow(op: Op): String? = when {
        OpFacts.isCreation(op) -> OpFacts.createdId(op)
        op.opType == OpType.REVIEW_LOGGED -> (op.payload.members["id"] as? JsonString)?.value
        else -> null
    }
}
