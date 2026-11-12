# HANDOFF T108a

STATUS: DONE
BRANCH: t/T108a-search-parser
CHECK: `make check MODULE=core` → green; `make check MODULE=web` → green; `contracts/core/check.sh` → OK

## Changed
- `core/.../kotobaya/search/`: `DefaultSearchQueryParser` (parse/format/complete), lexer, tree parser, formatter, completer, highlight tokens.
- `core/.../kotobaya/core/KotobayaCore.kt`: `@JsExport kotobayaCore()` + `CoreJsImpl` (parseSearch, completeSearch; renderCard/previewIntervals throw NotImplementedError). Second of T107b/T108a to merge rebases.
- Tests in `core/src/commonTest/.../search/` (`SearchExamples.kt` generated from the tsv + check_search.py `fmt`).

## Acceptance → tests
- `+` lines parse, canonical == check_search.py, round trip, fixed point → `validExamplesParseFormatCanonicalAndRoundTrip`
- `-` lines give implied code → `invalidExamplesYieldTheImpliedErrorCode`
- Random trees round trip (3000, seed fixed; Not(And/Or), Or in And, `or`/`-a`/`a:b`) → `randomWellFormedTreesRoundTrip`
- Deterministic completion → `completesKeysByPrefixThenSubstring`, `completesOperatorsOnlyAfterAnOperand`, `completesFixedAndVocabularyValues`, `completeNeverThrows`
- Also precedence/flattening, spans, format rejects ill-formed trees, number formatting.

## New dependencies
none

## For the reviewer
- `SearchExamples.kt` is a snapshot of the tsv (commonTest cannot read files): regenerate if the tsv changes. Codes I chose where notes did not imply one: `a=b` UNKNOWN_KEY, `(a)(b)` UNBALANCED_PAREN, `()` UNBALANCED_PAREN, operator glued to a paren DANGLING_OPERATOR.
- Completion choices not in the contract: `rated:` offers 1/7/30/365 (and 1-4 after the second colon); built-in kinds are the CardGenerator kinds; JVM `Double.toString` is expanded to non-exponent text (JDK17 may not always give the shortest digits, round trip still exact). js tests not run (only jvmTest in `make check`).
