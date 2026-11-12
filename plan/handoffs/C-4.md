# HANDOFF C-4

STATUS: DONE
BRANCH: t/C-4-jpdb-adr009
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- openapi.yaml `importJpdbHistory`: ADR-009 rules (one `meaning` card per seq group; en_jp rows ignored; merge by seq, drop exact dups; known-only → status; writing/known-only kanji cards created via `card.created suspended=true`; optional kanji `keyword`; exact UUIDv7 review-id formula).
- `JobResultJpdbHistory`: additive required `reviews_duplicate_dropped`, `vocabulary_en_jp_ignored`, `kanji_cards_suspended`; example fixed. SUMMARY.md C-4 note; clients regenerated.

## Acceptance → tests (bucket arithmetic for T207a)
- `vocabulary_total = vocabulary_carded + vocabulary_status_only + vocabulary_en_jp_ignored + len(unmapped)`
- `kanji_total = kanji_carded + len(kanji_unmapped)`
- `reviews_total = reviews_imported + reviews_already_imported + reviews_skipped + reviews_unknown_grade + status_events_unknown + status_events_known + reviews_duplicate_dropped`
- Event bucket = first match: en_jp/unmapped → skipped; malformed → unknown_grade; dup → dropped; `unknown`; `known`; other non-rating → unknown_grade; import_reviews=false → skipped; id exists → already_imported; else imported.
- `notes_created + notes_existing` = distinct carded seqs; `cards_created ≤` that; `kanji_cards_suspended ≤ kanji_cards_created`.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Decided beyond ticket: known-only status is not written over an existing override; existing cards keep suspension state.
- `JpdbHistoryImportRequest.import_word_status` still mentions never-forget/blacklist (not owned) → FOLLOWUPS.
