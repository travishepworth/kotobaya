package kotobaya.android.shell.api.actions

import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.get
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.mint.MintRequest

private val OVERRIDES = setOf("known", "never_forget", "blacklisted", "ignored")

/** Largest `entry_seq` an op can carry (common.schema.json `entry_seq`, C-6). */
private const val MAX_SEQ = 9_007_199_254_740_991L

/** The `{seq}` path parameter: an integer of at least 1, else `422` at `/seq` (server `WordStatusApi.seq`). */
private fun seqOf(call: LocalCall): Long =
    call.path("seq").toLongOrNull()?.takeIf { it in 1..MAX_SEQ }
        ?: throw ProblemException(Problems.validationFailed("/seq", "invalid_value", "Must be an integer of at least 1."))

private fun seqKey(seq: Long) = listOf(JsonNumber(seq.toDouble()))

/** `getWordStatus`: the row, or `new` with every other property null. The entry need not exist in the dictionary. */
internal fun getWordStatus(call: LocalCall): ShellResponse {
    call.query()
    val seq = seqOf(call)
    return ShellResponse.json(call.services.get<SqlDelightDeviceStore>().read { ActionViews.wordStatus(it.learning, seq) })
}

/**
 * `setWordStatus`: one `word.status_set` with `origin = manual` (the payload `ReviewActions` and `DeckWordOps` mint).
 * Setting the status the entry already has still mints (the register's clock moves forward, as the server).
 */
internal fun setWordStatus(call: LocalCall): ShellResponse {
    call.query()
    val seq = seqOf(call)
    val body = call.bodyObject()
    val e = FieldErrors()
    e.unknownKeys(body, setOf("status"))
    val status = e.enumValue(body, "status", OVERRIDES, required = true)
    e.throwIfAny()
    val payload = jsonObj("entry_seq" to JsonNumber(seq.toDouble()), "status" to JsonString(checkNotNull(status)), "origin" to JsonString("manual"))
    val result = call.services.get<LocalWrites<SqlLearningTables>>().exclusive<JsonObject> { _ ->
        WritePlan.Mint(listOf(MintRequest(OpType.WORD_STATUS_SET, payload)), { "/status" }) { after, _ -> ActionViews.wordStatus(after.learning, seq) }
    }
    return ShellResponse.json(result)
}

/** `clearWordStatus`: one `word.status_cleared`; no override (or no row) → `200` with the current status, nothing minted. */
internal fun clearWordStatus(call: LocalCall): ShellResponse {
    call.query()
    val seq = seqOf(call)
    val result = call.services.get<LocalWrites<SqlLearningTables>>().exclusive<JsonObject> { tx ->
        val row = tx.learning.get(LearningTable.WORD_STATUS, seqKey(seq))
        if (row == null || row["override_status"].isNullish()) return@exclusive WritePlan.Answer(ActionViews.wordStatus(tx.learning, seq))
        WritePlan.Mint(listOf(MintRequest(OpType.WORD_STATUS_CLEARED, jsonObj("entry_seq" to JsonNumber(seq.toDouble())))), { "" }) { after, _ ->
            ActionViews.wordStatus(after.learning, seq)
        }
    }
    return ShellResponse.json(result)
}
