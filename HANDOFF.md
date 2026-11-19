# HANDOFF C-28

STATUS: DONE
BRANCH: t/C-28-contracts (rebased on main ce39f012; includes 3b / ADR-042)
CHECK: `make check MODULE=contracts` → green, no drift; core compiles (jvm + js)

## Changed (≤ 6 lines)
- core contracts: `QueueBuilder.kt` rules 4–6 (kanji units, backfill, withheld, open group, per-visit scope), `ReviewLoop.kt` (`untilStop`), new `KanjiRequirement.kt`, `DeckProgress.kt`, `KanjiParts.kt`, `frontFurigana` on render inputs
- openapi: `loop.until_stop`, `QueueItem.kanji_parts` / `front_furigana`, `Deck.progress`, C2 rewritten; clients regenerated
- SCHEMA.md 6.2 (add_kanji / kanji_before_vocab deprecated), 6.5 `cards.kanji_exemptions`
- README "C-27" rewritten (step A kanji notes), test lists; SUMMARYs; REVIEW-LOOP.md §13
- shell: 6 new parity scenarios, 7 mandatory tags; `getDeckCoverage` stays proxy

## Acceptance → tests
- contracts green → `make check MODULE=contracts`
- new scenarios pin order + `until_stop` → `queue-kanji-*`, `queue-until-stop`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- 海老/林檎/薔薇/今日 are not in `fixtures/dict` (not my path): scenario uses 寿司/昨日/鱈/鱈子. 林檎, 薔薇 are probably `uk` in JMdict (unverified) → exempt, contradicting ADR-042's examples; 薔 has freq 2356.
- 13 old parity scenarios moved to kana-only entries; all goldens stale until SRV-28 re-records. Server/android tests pinned to those seqs may break.
- Hand-evaluated expects; `add_kanji: off` no longer opts out; units larger than `new_per_day` need Keep going.
