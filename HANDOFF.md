# HANDOFF C-8 (attempt 2)

STATUS: DONE
BRANCH: t/C-8-sync-gaps (rebased on main)
CHECK: `make check MODULE=contracts` → green; `core` → green; `server` → green

## Changed (≤ 6 lines)
- SYNC.md 10.4:
  - Step 1a: a clock-coded op that writes a window row goes to E.
  - New step 1c: `e:<seq>` items and `word.status_*` of window notes are window-derived. They go to E, ordered after their note. Step 6a re-keys those items to `n:<note>`.
  - The discard list (with `D`) is now a MUST.
- SYNC.md 9.4: queues MUST skip refused cards. 6.3: startup step 3 runs in every case. New backup job step 4 records `L` (the live `durable_seq`); the restore tool warns from it.
- Core contract KDoc: `QueueBuilder` rule 4 rewritten per decision 6 (atomic group; kanji charged to their own home deck, never skipped). `candidates` excludes refused cards.
- 3 remint-scenarios regenerated (mining kept; status re-minted). `validate.py` checks the 6a payloads. Section 13 formatting fixed.

## Follow-ups
- `core/…/replay/IncrementalReplay.kt`: on refusal, hold the refused cache, stay consistent, never fail a pull page.
- `DefaultReplayEngine.decide`: add step 1b.
- Android op-apply and rebuild replay callers write the refused cache.
- T205 (breaking):
  - Rewrite `QueueBuilderTest.kt:345` per decision 6.
  - Update `DefaultQueueBuilder`.
  - Loaders exclude refused cards.
- Device re-mint: implement the new E rules (1a/1c, ordering, `n:` re-key), the `D` list, and the MUST-show discard list.
- Server:
  - `CoreCardCache` writes the refused cache.
  - With backups disabled, persist `durable_seq` (startup step 3 and every `change` transaction).
  - Run the remint-scenarios (honour `after_seq`).
- T112: backup metadata `L` and the restore warnings.

## Interim state is safe
- Nothing deployed relies on the new rules: Re-mint is not implemented on any device yet, and replay refusals cannot happen with the schema's checks in place.
- The one hazard: do NOT switch backups from disabled to enabled before the server `durable_seq` follow-up lands, or the reported `durable_seq` drops.

## New dependencies
- none

## For the reviewer
- A window-derived word status is re-minted at its estimated time, so it can beat another device's earlier status for that entry.
- The 1a clock-branch case has no scenario. It only occurs when a span's `sentence_id` is in W, and that cannot really happen because sentences are not op rows.
