package kotobaya.sync

import kotobaya.contracts.HlcString
import kotobaya.contracts.sync.HlcParts
import kotobaya.contracts.sync.HlcState
import kotobaya.server.SERVER_DEVICE_ID
import kotobaya.server.ServerStateKeys
import org.jooq.DSLContext

/**
 * The server's HLC (SYNC.md 3.2) for one op transaction. `last` lives in `app.server_state` key
 * `hlc_last` (full HLC string, server device id; FOLLOWUPS "Freeze A"), is read once when the
 * transaction starts — after `pg_advisory_xact_lock(7033001)`, so no other transaction can mint or
 * observe concurrently — and written back by [persist] in the same transaction (3.2: "persisted in
 * the same transaction as the op that was minted or applied"). Restore finalization
 * (`StartupSequence`) raises the same key; this class never lowers it.
 */
class ServerClock internal constructor(private val dsl: DSLContext, private val physicalMs: () -> Long) {
    private val loaded: HlcString? = dsl.fetchOptional("select value from app.server_state where key = ?", ServerStateKeys.HLC_LAST)
        .map { it.get(0, String::class.java) }.orElse(null)
    private var state: HlcState = DefaultHlcRules.stateOf(loaded)
    private var dirty = false

    /** `mint()` with the server device id. */
    fun mint(): HlcString {
        val m = DefaultHlcRules.mint(state, physicalMs(), SERVER_DEVICE_ID)
        state = m.state
        dirty = true
        return m.hlc
    }

    /** `observe(h)`: called for every accepted pushed op before anything is minted after it. */
    fun observe(hlc: HlcString) {
        val next = DefaultHlcRules.observe(state, hlc)
        if (next != state) {
            state = next
            dirty = true
        }
    }

    /** The current `last` as a full HLC string (server device id). */
    fun last(): HlcString = DefaultHlcRules.format(HlcParts(state.ms, state.counter, SERVER_DEVICE_ID))

    internal fun persist() {
        if (!dirty) return
        dsl.execute(
            "insert into app.server_state (key, value, updated_at) values (?, ?, now()) " +
                "on conflict (key) do update set value = excluded.value, updated_at = now()",
            ServerStateKeys.HLC_LAST, last(),
        )
        dirty = false
    }
}
