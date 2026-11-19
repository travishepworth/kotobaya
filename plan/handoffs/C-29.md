# HANDOFF C-29

STATUS: DONE
BRANCH: t/C-29-audio
CHECK: `make check MODULE=contracts` → green (attempt 2; core Kotlin changed in KDoc only since the green `:core:jvmTest` of attempt 1, not re-run)

## Changed (≤ 6 lines)
- Attempt 1: V4 `tts_audio`, SCHEMA.md 6.6, `QueueItem.word_audio`, `resolveSpeech`, `WordAudio.kt`, render "Word audio", REVIEW-LOOP §14
- Fix 1: `resolveSpeech` needs `write` for every request shape; urgent is no exemption: hard limit 340 queued jobs (300 + reserve 40)
- Fix 2/3: `P(now)` asks today's new cards first, early stop never skips them; "Backlog" no longer promises the sweep for arbitrary texts
- Fix 4/5: `audio:` prefix reserved (only `audio:word`); note whose `[sound:]` blob is missing → `failed`, source `note`
- Fix 6: device reports a `tts` hash offline only when cached (SCHEMA 6.6 rule 6, SHELL.md 4.3)

## Acceptance → tests
- contracts green; examples for both note shapes → `WordAudio` schema examples, `SpeechResolveResult*`, WordAudio.kt table

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Deviation from finding 1: `note_id`-only requests also need `write` (one rule, no per-item scope). Read-only clients get audio via `getReviewQueue`'s own enqueue.
- Fix 6 makes the local `tts` hash depend on connectivity; `note` hashes are unchanged (parity).
- The three open ADR-047 choices are untouched.
