package kotobaya.sync.restore

/**
 * `durable_seq` as the server reports it in every `PushResponse` / `PullResponse` (SYNC.md 6.2, 6.3).
 *
 * - Backups enabled (the default): the `app.server_state` row `durable_seq` (absent = 0), written by
 *   the backup job after a verified backup and by restore finalization; never reported above
 *   `high_water_seq`.
 * - Backups disabled ([BACKUPS_DISABLED_ENV] = `true`): `high_water_seq`. There is nothing the server
 *   could be restored from, so every committed op is as durable as it will ever be; reporting 0 would
 *   make devices keep every acked op forever (SYNC.md 5.4 purge rule).
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

    /** The reported value: `min(stored, highWater)`, or `highWater` with backups disabled. Never negative. */
    fun reported(stored: Long, highWater: Long, backupsDisabled: Boolean): Long =
        if (backupsDisabled) highWater else minOf(stored, highWater).coerceAtLeast(0)
}
