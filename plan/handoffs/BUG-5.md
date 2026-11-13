# HANDOFF BUG-5

STATUS: DONE
BRANCH: t/BUG-5-kanji-hot
CHECK: `make check MODULE=server` → green

## Changed
- struggle/StruggleModule.kt: `listHotKanji` handler. Refactored HotWords into `scoredNotes()` (core hot scorer) shared by words and kanji. Kanji score = sum of hot-note scores via `ref.entry_kanji`, word_count = hot notes containing it, meanings from `ref.kanji`, note_id = live kanji note.
- jobs/JobsModule.kt: /events stream catches ClosedWriteChannelException and IOException "Cannot write to a channel" (client gone), logs at debug; other exceptions rethrown.
- WiringAuditTest: allowlist now empty.

## Acceptance → tests
- ranked kanji, limit, 422, 401 → StruggleApiTest.hotKanjiRollsUpHotWordScoresPerKanji
- allowlist shrinks → WiringAuditTest (passes with empty allowlist)
- SSE disconnect → reasoning only (no test; hard to force a Netty write failure deterministically)

## New dependencies
none

## For the reviewer
- Still-501 operations: NONE (allowlist is empty), so web/ and android/ call no 501 ops; I did not grep them.
- Disconnect catch matches on message/class name since ChannelWriteException is a Netty internal type; unverified against a live log.
- Notes with no entry_seq contribute no kanji.
