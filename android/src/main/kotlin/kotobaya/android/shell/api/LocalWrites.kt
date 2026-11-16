package kotobaya.android.shell.api

import kotobaya.contracts.sync.Op
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.StoreTransaction

/** What a writing handler decided inside its transaction (see [LocalWrites.write]). */
sealed interface WritePlan<L, out T> {
    /**
     * Mint [requests] (in order, all or none) as the device, then build the answer with [respond]
     * in the same transaction, after the ops were applied.
     */
    class Mint<L, T>(
        val requests: List<MintRequest>,
        /** Maps a [MintRefusal.Invalid] op pointer to the request property it came from (L-3), `""` if none. */
        val invalidPointer: (MintRefusal.Invalid) -> String = { "" },
        val respond: (StoreTransaction<L>, List<Op>) -> T,
    ) : WritePlan<L, T>

    /** Nothing to mint (idempotent retry, a change that changes nothing, L-2 / L-4): answer [value]. */
    class Answer<L, T>(val value: T) : WritePlan<L, T>
}

/**
 * THE helper every writing handler uses (rule L-2, CLAUDE.md rule 8): runs the handler's [block]
 * and the mint in ONE `store.transaction` (the check-then-mint of L-4 is therefore atomic), maps a
 * [MintRefusal] to its L-3 problem, and after commit calls [onLocalChange] (production:
 * `SyncTriggers.localChange()`) exactly once when something was minted. No handler writes a table:
 * the local effect is the op-apply path inside [OpMinter.mintAll].
 *
 * A refusal, or a [ProblemException] thrown by [block], rolls the whole transaction back and
 * propagates as [ProblemException]; nothing is minted, applied or queued and no sync is triggered.
 * Must not be called inside another store transaction (the trigger would fire before the commit).
 */
class LocalWrites<L>(
    private val store: DeviceStore<L>,
    private val minter: OpMinter<L>,
    private val onLocalChange: () -> Unit,
) {
    fun <T> write(block: (StoreTransaction<L>) -> WritePlan<L, T>): T {
        var minted = false
        val result = store.transaction { tx ->
            when (val plan = block(tx)) {
                is WritePlan.Answer -> plan.value
                is WritePlan.Mint -> {
                    if (plan.requests.isEmpty()) {
                        plan.respond(tx, emptyList())
                    } else {
                        when (val r = minter.mintAll(plan.requests)) {
                            is MintResult.Refused -> throw ProblemException(Problems.fromMintRefusal(r.reason, plan.invalidPointer))
                            is MintResult.Minted -> {
                                minted = true
                                plan.respond(tx, r.ops)
                            }
                        }
                    }
                }
            }
        }
        if (minted) runCatching { onLocalChange() }
        return result
    }
}
