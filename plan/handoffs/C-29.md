# HANDOFF C-29

STATUS: DONE
BRANCH: t/C-29-audio
CHECK: `make check MODULE=contracts` → green (also `:core:jvmTest :core:compileKotlinJs` green)

## Changed (≤ 6 lines)
- db: `V4__tts_audio.sql` (+ smoke), device table `tts_audio` in `Local.sq`, SCHEMA.md 6.6 (rule book), settings `review.autoplay_audio`, `tts.voice`, `audio.prefetch_days` 0–365
- api: `QueueItem.word_audio`, `POST /tts/resolve` (`resolveSpeech`, shell class `proxy`), SHELL.md 4.3
- core contract: `render/WordAudio.kt` (`WordAudioResolver`), `RenderInput.wordAudio`, `RenderedCard.wordAudio`/`autoplayAudio`, `{{audio:word}}`, JS facade fields
- docs/REVIEW-LOOP.md §14 (screen rules A1–A6); clients regenerated

## Acceptance → tests
- contracts green; both examples (deck-materialized empty fields; own `[sound:]`) → `WordAudio` schema examples, `SpeechResolveResult*`, WordAudio.kt table, REVIEW-LOOP §14

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Identity is the pair (voice, text), not a hash; audio is never written to notes (no op). VOICEVOX gets kana only (accent guessed).
- Tickets + deps: `contracts/api/SUMMARY.md` "C-29 changes": CORE-29 → WRK-29, SRV-29 (re-records parity goldens) → WEB-29, AND-29.
- WRK-29: `worker/tests/dict/conftest.py` lists migrations by name; add V4. Vocab only; `listening` creation rule unchanged.
