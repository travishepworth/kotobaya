package kotobaya.sync.restore

import kotobaya.server.ServerStateKeys
import org.jooq.DSLContext

/**
 * `durable_seq` (SYNC.md 6.2, 6.3, C-8).
 *
 * What it *is* never depends on the configuration: the persisted row `app.server_state.durable_seq`
 * (absent = 0), reported in every `PushResponse` / `PullResponse` as `min(row, high_water_seq)`
 * ([reported]). The configuration only changes who writes the row:
 *
 * - Backups enabled (the default): the backup job after a verified backup, and restore finalization.
 * - Backups disabled ([BACKUPS_DISABLED_ENV] = `true`): additionally the server, with the backup
 *   job's `greatest()` upsert ([raiseToHighWater]), (a) in the startup transaction ([raiseOnStartup],
 *   6.3 startup step 3) and (b) in every transaction that inserts `change` rows (`OpTx.finish`, the
 *   only insert path), under lock 7033001. So while disabled the row equals `high_water_seq`, and
 *   re-enabling backups lowers nothing: the row keeps its value until a verified backup passes it.
 *
 * The Helm chart sets `BACKUPS_DISABLED=true` on the api when `backup.enabled=false`
 * (`deploy/helm/kotobaya/templates/api.yaml`).
 */
object DurableSeq {
    /** Environment variable naming the "backups disabled" setting (FOLLOWUPS "Freeze B", T112). */
    const val BACKUPS_DISABLED_ENV = "BACKUPS_DISABLED"

    /** True iff [BACKUPS_DISABLED_ENV] is `true`, `1` or `yes` (case-insensitive), like the other boolean settings. */
    fun backupsDisabled(env: Map<String, String> = System.getenv()): Boolean =
        env[BACKUPS_DISABLED_ENV]?.trim()?.lowercase() in setOf("1", "true", "yes")

    /** The reported value: `min(row, highWater)`, never negative, whatever the configuration (6.3). */
    fun reported(stored: Long, highWater: Long): Long = minOf(stored, highWater).coerceAtLeast(0)

    /**
     * Raises the row to `coalesce(max(change.seq), 0)` with the backup job's `greatest()` upsert (6.3
     * step 3); never lowers it. The caller holds lock 7033001 (an op transaction or the startup
     * transaction). Returns the row's value after the upsert.
     */
    fun raiseToHighWater(dsl: DSLContext): Long {
        val hw = (dsl.fetchValue("select coalesce(max(seq), 0) from app.change") as Number).toLong()
        val after = dsl.fetchValue(
            "insert into app.server_state (key, value, updated_at) values (?, ?, now()) " +
                "on conflict (key) do update set value = greatest(app.server_state.value::bigint, excluded.value::bigint)::text, " +
                "updated_at = now() returning value::bigint",
            ServerStateKeys.DURABLE_SEQ, hw.toString(),
        ) as Number
        return after.toLong()
    }

    /**
     * SYNC.md 6.3 server startup step 3 (C-8): in the startup transaction, after restore finalization
     * or epoch creation, in every case: with backups disabled raise the row ([raiseToHighWater]); with
     * backups enabled do nothing. The caller holds lock 7033001.
     */
    fun raiseOnStartup(dsl: DSLContext, backupsDisabled: Boolean) {
        if (backupsDisabled) raiseToHighWater(dsl)
    }
}
