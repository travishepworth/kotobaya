# HANDOFF C-28e

STATUS: DONE
BRANCH: t/C-28e-contracts
CHECK: `make check MODULE=contracts` → green (`:core:compileKotlinJvm` also green; core tests not run, they will fail until CORE-28c)

## Changed (≤ 6 lines)
- `KanjiRequirement.kt`: `learnableKanji` = learnable kanji regardless of tags; `frontFurigana` = `wordExempt` OR no learnable kanji; worked examples as triples. KDoc only, no signature change.
- `QueueBuilder.kt` KDoc (`requiredKanji`, rule 4 wording only), SCHEMA.md 6/6.5 (+ "no migration" note), core README test list, openapi (`front_furigana`, queue text), SHELL.md, REVIEW-LOOP.md, SUMMARYs; clients regenerated.
- `queue-kanji-exemptions.json` step 0: 寿 司 寿司(furigana) 昨 日 昨日 鱈 子 鱈子, 9 items, `until_stop` 9. Step 2 expects unchanged.

## Acceptance → tests
- contracts green; expects hand-evaluated → only `queue-kanji-exemptions` changes (no other scenario uses a tagged displayed form; 学校/食べる forms are untagged).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Kept the names `wordExempt` / `EXEMPT_FORM_TAGS` (now "word-level tagged, furigana only") so callers compile; rename is a follow-up choice.
- Must change: core `DefaultKanjiRequirement` steps 2-3 + `KanjiRequirementTest`; server and android only if they read `wordExempt` or cache `requiredKanji`/furigana (else just pick up core); rerun parity.
- 老, 林, 檎, 今 are not in the fixture KANJIDIC; their grades in the KDoc are from memory.
