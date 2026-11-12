-- Write durable_seq in the LIVE database (contracts/sync/SYNC.md 6.3 step 3), in one transaction that
-- holds the same advisory lock as restore finalization and every op apply (7033001), so it cannot
-- interleave with the server's finalization (a backup of the pre-restore log must never write its S
-- into the new epoch).
-- :s = S, max change.seq contained in the verified dump; :e = E, the dump's log_epoch.
-- Writes only if no restore is pending AND the live log_epoch still equals E, and monotonically.
BEGIN;
SELECT pg_advisory_xact_lock(7033001) \gset
WITH w AS (
  INSERT INTO app.server_state (key, value, updated_at)
  SELECT 'durable_seq', :'s', now()
  WHERE NOT EXISTS (SELECT 1 FROM app.server_state WHERE key = 'restore_pending')
    AND (SELECT value FROM app.server_state WHERE key = 'log_epoch') = :'e'
  ON CONFLICT (key) DO UPDATE
    SET value = greatest(app.server_state.value::bigint, excluded.value::bigint)::text,
        updated_at = now()
  RETURNING value)
SELECT coalesce((SELECT 'written durable_seq=' || value FROM w),
                'skipped: restore pending, or live log_epoch differs from the dump''s (a restore happened in between)');
COMMIT;
