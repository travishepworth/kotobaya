# HANDOFF T507

STATUS: DONE
BRANCH: t/T507-grammar-cards
CHECK: `make check MODULE=core` green; `make check MODULE=server` green (final run after fixing test fixture inserts)

## Changed (≤ 6 lines)
- core `cards/grammar/GrammarCardSentences.kt`: pure picker (rotation, i+0 first, span validation) + `toCloze` feeding the existing renderer (cloze/spot/recognition already rendered by `DefaultCardRenderer`).
- server `grammar/cards/GrammarCardSentenceSource.kt`: queries mined sentences with live spans, uses the core picker. No API op exists for it (not exposed).
- F-20: `GRAMMAR_SEED_ON_START=true` seeds once after startup (Main.kt + `ComposedApp.seedGrammar`); 3 duplicate titles retitled in fixtures and resources; validate.py and GrammarApiTest check unique titles per level.

## Acceptance → tests
- Rotation → `GrammarCardSentencesTest.sentenceRotatesAcrossConsecutiveReviews`, `GrammarCardSentenceSourceTest.rotatesEachReviewAndPrefersIPlusZero`
- i+0 preferred → `iPlusZeroIsPreferredOverUnknownWords`, same server test
- Cloze UTF-16 → `clozeBlanksExactlyTheSpanInUtf16WithNonBmpText`, `invalidSpansAreNeverPicked`
- Unique seed titles → `GrammarApiTest.seedIsValidAndSeedingTwiceIsIdempotent`, validate.py

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Edited `server/.../Main.kt` (outside listed paths, minimal) for the seed trigger; server now starts with `wait=false` then joins.
- Grammar card creation reuses `POST /notes` (grammar_point_id); no new code. Tatoeba has no spans, so only span-tagged sentences are candidates. Production kind skipped.
