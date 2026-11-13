# FSRS weights publication (C-16)

How an `fsrs-optimize` job (public `JobKind`, run by the worker) gets new FSRS weights into
`app.fsrs_params`. The worker never writes that table (CLAUDE.md rule 8): it hands its result to
the server through the server-internal job kind `fsrs-params-publish`, and the server mints
`fsrs_params.published` (`contracts/sync/OPS.md`) through OpApply. Job-table rules for internal
kinds: `SCHEMA.md` 4.4 (C-9). Same shape as `MEDIA_PIPELINE.md` 3. Key words MUST / MUST NOT are
normative. Implemented by T606.

| File | What |
|---|---|
| `optimizer/fsrs-params-publish.v1.schema.json` | The child's payload (closed). |
| `optimizer/examples/*.json`, `*.expected.json` | Worked payloads with the op and result the runner produces. |
| `optimizer/validate.py` | The runner's decision as a pure function (`plan`), self-tested by `check.sh`. `validate.py publish <payload.json> <stored weights_version>` prints the op and result. |

## 1. Worker: the `fsrs-optimize` job (parent)

Payload `JobPayloadFsrsOptimize` `{preset_id}`, result `JobResultFsrsOptimize`
(`contracts/api/openapi.yaml`), dedupe key `fsrs-optimize:<preset id>`. Per attempt:

1. **Already published?** If an `fsrs-params-publish` row with `payload->>'parent_job_id'` = the
   parent's id has `status = 'succeeded'`, end the parent `succeeded` with that row's `result`,
   `progress = 1`, and stop.
2. **Plan**, reading in **one** `REPEATABLE READ` snapshot the preset row and the reviews:
   - Preset row missing → the attempt fails with `unknown_preset: …`.
   - Preset tombstoned (`deleted_at` not NULL) → not published, `reason: preset_deleted`.
   - `base_weights_version` := the row's `weights_version`. *Current weights* := the row's
     `weights`, or when that is NULL (never optimized) the built-in defaults = py-fsrs 6.3.2
     `DEFAULT_PARAMETERS` = the core's `Scheduler.defaultWeights`.
   - Which `review_log` rows are loaded, the minimum count, and how they are split into a
     training set and a held-out set are T606's (worker configuration), within these rules: the
     two sets are disjoint; the held-out set is not used for training in any way; it is
     non-empty and so is the training set.
   - Fewer rows than the configured minimum → not published, `reason: too_few_reviews` (no
     training, `log_loss`, `rmse_bins`, `log_loss_before` null, `holdout_review_count` 0).
3. **Train and gate.** Train on the training set. Let `weights` be the final 21 numbers after
   any clamping or rounding the worker applies. Then, in this order:
   - a weight non-finite or outside its bound in
     `contracts/sync/ops/fsrs_params.published.schema.json` → not published, `reason:
     out_of_bounds` (`log_loss`, `rmse_bins` null);
   - compute on the held-out set `log_loss_before` (current weights) and `log_loss_after`,
     `rmse_bins` (`weights`), with one and the same procedure (mean binary log loss of the
     predicted retrievability against recalled / not recalled);
   - **publish only if `log_loss_after < log_loss_before`**, both finite. Equal, worse, or
     non-finite → not published, `reason: not_improved`.
   "Not published" means: insert **no** child; end the parent `succeeded` with `published:
   false`, `weights_version` = `base_weights_version`, `review_count` = rows loaded, the stats
   computed so far, and the `reason`. The preset is untouched and no op exists.
4. **Hand off.** Insert the child in one statement, with `optimized_at` = now (worker clock, ms):

   ```
   INSERT INTO app.job (id, kind, payload, dedupe_key, max_attempts)
   VALUES (<new UUIDv7>, 'fsrs-params-publish', <payload>, 'fsrs-params-publish:<parent id>', 1)
   ON CONFLICT DO NOTHING
   ```

   then read the `queued` or `running` row holding that `dedupe_key` (a child left by an earlier
   attempt is reused, not duplicated, even though its weights are that attempt's). If nothing was
   inserted and no such row is found (the earlier child just ended), go back to step 1.
5. **Wait** for the child (poll, heartbeat the parent, honour parent cancellation):
   - `succeeded` → parent `succeeded`, `result` = the child's `result`, `progress = 1`;
   - `failed` → the parent attempt fails with the child's `error` (retried per `max_attempts`;
     a retry starts at step 1, so after `stale_base` it measures against the new weights);
   - `cancelled` → the parent is already `cancelled`; nothing to do.

Parent `progress` stays in [0, 0.9] until the child ends.

## 2. Server: `fsrs-params-publish` (child) and `FsrsParamsPublishRunner`

### 2.1 Payload

`optimizer/fsrs-params-publish.v1.schema.json`; kept when the row ends.

| Member | Meaning | Example |
|---|---|---|
| `format_version` | `1`. | `1` |
| `parent_job_id` | The public `fsrs-optimize` parent. | `01931f42-0000-7000-8000-000000000b01` |
| `preset_id` | The preset; equals the parent's `payload.preset_id`. | `00000000-0000-7000-8000-000000000010` |
| `base_weights_version` | The preset's `weights_version` in the worker's snapshot (0 = never optimized). The op gets `base_weights_version + 1`. | `0` |
| `fsrs_version` | `"6"`. | `"6"` |
| `weights` | Exactly the 21 numbers that were evaluated. | `[0.212, …, 0.1542]` |
| `optimized_at` | When the run finished, ms since the epoch, worker clock. | `1733584666631` |
| `review_count` | Rows used, training + held-out (≥ 2). | `5321` |
| `holdout_review_count` | Rows in the held-out set (≥ 1, < `review_count`). | `1064` |
| `log_loss_before` | Held-out log loss of the current weights (> 0). | `0.3655` |
| `log_loss_after` | Held-out log loss of `weights` (≥ 0, < `log_loss_before`). | `0.3412` |
| `rmse_bins` | Held-out RMSE (bins) of `weights` (≥ 0). | `0.0312` |

### 2.2 Runner

The only claimer of the kind. Claim, heartbeat and the stale rule are those of
`MEDIA_PIPELINE.md` 3.3 with `kind = 'fsrs-params-publish'` (claim `FOR UPDATE SKIP LOCKED`; a
`running` child with `heartbeat_at` older than 5 minutes is re-queued whatever its `attempts`).

**Before the transaction** (no locks held):

1. Validate the payload: `format_version` ≠ 1 → `unsupported_format`; any number non-finite,
   schema violation, or `holdout_review_count ≥ review_count` → `invalid_payload`; **not
   (`log_loss_after < log_loss_before`) → `not_improved`** (the runner enforces the gate too; it
   never trusts the worker to have applied it).
2. Build the op payload (2.3) and validate it against
   `contracts/sync/ops/fsrs_params.published.schema.json`; a violation → `weights_out_of_bounds`
   (OPS.md C-1: the server validates every op it mints). The runner MUST NOT clamp.
3. Read the parent: missing, `kind` ≠ `fsrs-optimize`, or its `payload.preset_id` ≠ the child's
   `preset_id` → `invalid_payload`. Parent not `running` → end the child `cancelled`; stop.

**The transaction**, under the op-apply lock (`pg_advisory_xact_lock(7033001)`, taken first):

4. Re-check as `MEDIA_PIPELINE.md` 3.3 step 4: the child is still `running` and `locked_by` =
   this runner (else roll back and stop); the parent is `running` (else end the child
   `cancelled`, commit only that).
5. **Same parent, already published → no-op.** If another `fsrs-params-publish` row with the
   same `payload->>'parent_job_id'` has `status = 'succeeded'`: end this child `succeeded` with
   that row's `result` (copied verbatim), mint nothing, commit.
6. `SELECT … FROM app.fsrs_params WHERE id = preset_id FOR UPDATE`:
   - no row → `unknown_preset`;
   - tombstoned → mint nothing; end the child `succeeded` with the 2.4 result changed to
     `published: false`, `weights_version` = the stored one, `reason: preset_deleted`; commit;
   - stored `weights_version` ≠ `base_weights_version` → `stale_base` (the weights the worker
     compared against are no longer the preset's).
7. Mint **one** `fsrs_params.published` op through OpApply as a server op (new UUIDv7 `op_id`,
   server device id, server HLC, `caused_by_op_id` null, `change.token_id` null) with the 2.3
   payload. OpApply's own rule (applied iff `weights_version` > stored) then holds by step 6.
8. Child: `status = succeeded`, `result` (2.4), `progress = 1`, `finished_at`, `WHERE status =
   'running' AND locked_by = <this runner>` (0 rows → roll back). Parent: `progress =
   greatest(progress, 0.95)`, `progress_message = 'publishing'` `WHERE status = 'running'` (send
   its `job.updated` after commit).

Any error rolls the transaction back; the runner then ends the child `failed` with `error =
'<code>: <message>'` in a new transaction. Codes: `unsupported_format`, `invalid_payload`,
`not_improved`, `weights_out_of_bounds`, `unknown_preset`, `stale_base`, `internal` (unexpected
exception).

### 2.3 The op

| Op payload member | Value |
|---|---|
| `id` (= envelope `entity_id`) | `preset_id` |
| `weights_version` | `base_weights_version + 1` |
| `fsrs_version`, `weights`, `optimized_at` | copied unchanged |
| `optimizer_stats` | `{"review_count": review_count, "log_loss": log_loss_after, "rmse_bins": rmse_bins, "log_loss_before": log_loss_before, "holdout_review_count": holdout_review_count}` |

`log_loss_before` and `holdout_review_count` are new keys of the open `optimizer_stats` object
(the op schema preserves unknown keys; `Preset.optimizer_stats` shows them). Readers MUST treat
them as optional: presets published before C-16, or by a later format, may lack them.

### 2.4 The result (child's, then the parent's)

`JobResultFsrsOptimize`: `{preset_id, published: true, weights_version: base_weights_version + 1,
review_count, log_loss: log_loss_after, rmse_bins, log_loss_before, holdout_review_count, reason:
null}`.

## 3. Idempotency, retries, concurrency

- **A child publishes at most once.** Its `succeeded` status commits in the same transaction as
  the op, so a crash before commit leaves neither and the stale-heartbeat rule re-runs it from
  step 1; after commit the row is no longer claimable.
- **A parent publishes at most once.** Worker step 1 covers a retried parent. The remaining
  window (an earlier attempt's child succeeds after the retry's step 1 and before its insert, so
  the insert creates a second child) is closed by runner step 5, and independently by step 6:
  the second child's `base_weights_version` is one behind.
- **A preset never gets two publications from one comparison.** Every publication checks
  `base_weights_version` under the lock and the preset row lock, so two parents racing on one
  preset (only possible when a stale parent was re-queued while its first worker still runs;
  the parent dedupe key otherwise allows one) end as one publish and one `stale_base`. Versions
  increase by exactly one per op.
- **Re-running a succeeded job** (a new `optimizePreset` call, next week's schedule) is a new
  parent: it trains again and publishes only if it beats the weights now stored.
- **Cancellation.** Cancelling the parent before runner step 4 publishes nothing. A cancel that
  lands after the transaction committed leaves the parent `cancelled` and the weights
  published; the op is not reverted.
- **Deleted preset.** Never published to (worker step 2, runner step 6), although
  `fsrs_params.published` itself still applies to tombstones when replicated.
- **Clocks.** `optimized_at` is informational; ordering uses `weights_version` only, so worker
  clock skew cannot reorder or drop a publication.
- **Replay.** Applying the op makes every card of the preset stale (`card.weights_version` ≠ the
  preset's); replay is lazy and not part of this transaction (OPS.md, SYNC.md 9.2–9.3).

## 4. Worked examples

`optimizer/examples/fsrs-params-publish.first.json`: the Default preset, never optimized
(stored `weights_version` 0). Held-out log loss 0.3655 with the default weights, 0.3412 with the
new ones → the runner mints the op in `fsrs-params-publish.first.expected.json`
(`weights_version` 1) and the job result says `published: true`.

Run the same payload again (for instance a second child for the same parent slipped through):
step 5 ends it with the first child's result; without step 5, step 6 finds stored version 1 ≠
base 0 → `stale_base`. Either way no second op.

`fsrs-params-publish.republish.json`: a preset at version 3, improved from 0.3371 to 0.3312 →
version 4. Had the optimizer produced 0.3371 or more, the worker would have ended the parent
with `{"published": false, "weights_version": 3, "reason": "not_improved", "log_loss": 0.3371,
"log_loss_before": 0.3371, …}` and inserted no child; a child carrying such numbers fails with
`not_improved`.
