-- Write durable_seq in the LIVE database (contracts/sync/SYNC.md 6.3 step 3). One statement = one transaction.
-- :s = S, max change.seq contained in the verified dump; :e = E, the dump's log_epoch.
-- Writes only if the live log_epoch still equals E (otherwise a restore happened in between: write
-- nothing), and monotonically (never lowers an existing value).
WITH w AS (
  INSERT INTO app.server_state (key, value, updated_at)
  SELECT 'durable_seq', :'s', now()
  WHERE (SELECT value FROM app.server_state WHERE key = 'log_epoch') = :'e'
  ON CONFLICT (key) DO UPDATE
    SET value = greatest(app.server_state.value::bigint, excluded.value::bigint)::text,
        updated_at = now()
  RETURNING value)
SELECT coalesce((SELECT 'written durable_seq=' || value FROM w),
                'skipped: live log_epoch differs from the dump''s (a restore happened in between)');
