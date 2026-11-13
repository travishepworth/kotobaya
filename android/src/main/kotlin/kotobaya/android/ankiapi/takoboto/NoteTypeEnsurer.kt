package kotobaya.android.ankiapi.takoboto

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.OpIdSource

sealed interface EnsureResult {
    /** The live note type notes are created with. [created] = this call minted its `note_type.created`. */
    data class Ready(val noteTypeId: String, val created: Boolean) : EnsureResult

    /** It does not exist and could not be created (e.g. not paired yet). Retried on the next call. */
    data class Refused(val reason: MintRefusal) : EnsureResult
}

/**
 * Makes sure a live "Takoboto Vocab" note type exists, minting `note_type.created` at most once.
 * Check and mint run in ONE store transaction, so concurrent callers and repeated app starts mint once.
 *
 * Which type, in order:
 * 1. [TakobotoVocab.NOTE_TYPE_ID] if live (the usual case, on every device: the id is fixed);
 * 2. else the oldest `(created_at, id)` live vocab type named [TakobotoVocab.NAME] with the C-2 keys
 *    (e.g. one made on the web);
 * 3. else mint it: with the fixed id, or — only if a row with the fixed id exists but is deleted
 *    (deletion is permanent) — with a fresh UUIDv7.
 */
class NoteTypeEnsurer<L : LearningTables>(
    private val store: DeviceStore<L>,
    private val minter: OpMinter<L>,
    private val ids: OpIdSource,
    private val clock: Clock,
) {
    fun ensure(): EnsureResult = store.transaction { tx ->
        val tables = tx.learning
        val fixed = tables.get(LearningTable.NOTE_TYPE, listOf(JsonString(TakobotoVocab.NOTE_TYPE_ID)))
        if (fixed != null && isLive(fixed)) return@transaction EnsureResult.Ready(TakobotoVocab.NOTE_TYPE_ID, created = false)
        existingByName(tables)?.let { return@transaction EnsureResult.Ready(it, created = false) }
        val id = if (fixed == null) TakobotoVocab.NOTE_TYPE_ID else ids.newOpId(clock.nowMs())
        when (val r = minter.mint(OpType.NOTE_TYPE_CREATED, TakobotoVocab.createPayload(id))) {
            is MintResult.Minted -> EnsureResult.Ready(id, created = true)
            is MintResult.Refused -> EnsureResult.Refused(r.reason)
        }
    }

    private fun existingByName(tables: LearningTables): String? =
        tables.all(LearningTable.NOTE_TYPE)
            .filter { isLive(it) && it["name"] == JsonString(TakobotoVocab.NAME) && it["base_type"] == JsonString("vocab") && hasRequiredKeys(it) }
            .minWithOrNull(compareBy({ (it["created_at"] as? JsonNumber)?.value ?: 0.0 }, { (it["id"] as JsonString).value }))
            ?.let { (it["id"] as JsonString).value }

    private fun hasRequiredKeys(row: Row): Boolean {
        val keys = (row["fields"] as? JsonArray)?.items.orEmpty()
            .mapNotNull { ((it as? JsonObject)?.members?.get("key") as? JsonString)?.value }.toSet()
        return keys.containsAll(REQUIRED_KEYS)
    }

    private fun isLive(row: Row): Boolean = (row["deleted_at"] ?: JsonNull) == JsonNull

    private companion object {
        val REQUIRED_KEYS = setOf("expression", "reading", "meaning")
    }
}
