# HANDOFF C-8 (attempt 3)

STATUS: DONE
BRANCH: t/C-8-sync-gaps (rebased on main)
CHECK: `make check MODULE=contracts` → green; `core` → green; `server` → green

## Changed (≤ 6 lines)
- SYNC.md 10.4 step 6a:
  - An `e:<seq>` put is re-keyed `n:<note>` only if its owner note is live.
  - A removal is re-keyed only if E also re-keys a put of the same `(deck, e:<seq>)`. Otherwise it keeps `e:<seq>` and removes the shared row.
- Documented that Re-mint writes some shared registers, including the `clock_behind` caveat (accepted).
- `QueueBuilder` per-deck formula fixed: add_kanji cards are charged to their home deck, whichever source drew them.
- `remint-deck-items` gains three cases: removal kept `e:`, removal re-keyed, deleted owner. `model.py` updated; `validate.py` checks the payloads.
- Earlier attempts, kept:
  - 9.4: refused cache, which queues MUST skip.
  - 6.2/6.3: `durable_seq` persisted while backups are disabled, and backup metadata `L`.
  - 10.4: 1a/1c, list `D`, and the MUST-show discard list.
  - QueueBuilder rule 4 add_kanji groups; SCHEMA 6.1 canonical settings.

## Follow-ups
- Core:
  - `core/…/replay/IncrementalReplay.kt`: on refusal, hold the refused cache, stay consistent, never fail a pull page.
  - `DefaultReplayEngine.decide`: add step 1b.
- Android op-apply and rebuild replay callers write the refused cache.
- Refused cards are excluded from the queue loader in web, Android and T205.
- T205 (breaking):
  - Rewrite `QueueBuilderTest.kt:345` per decision 6.
  - Update `DefaultQueueBuilder`.
- Device re-mint: implement the new E rules (1a/1c, ordering, the `n:` re-key with the removal and live-owner conditions), the `D` list, and the MUST-show discard list.
- Port the new step 1 (1a/1c, `D`, the `n:` re-key and removal rule) into `server/src/test/kotlin/kotobaya/sync/fuzz/SimDevice.kt`.
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
- Re-keying is decided in E order, so a removal that comes before its own put keeps `e:<seq>`.
- The 1a clock-branch case has no scenario. It only occurs when a span's `sentence_id` is in W, and that cannot really happen.
