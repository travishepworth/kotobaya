# fixtures/coverage/

Licence: CC0 1.0. Self-written synthetic data (T404c); no real media, no jpdb data.

Two media decks for the coverage agreement test
(`server/src/test/kotlin/kotobaya/coverage/CoverageFixtureAgreementTest.kt`):

| File | Shape |
|---|---|
| `deck-a-show.json` | 2 segments, 23 entries, no unmapped tokens, 14 unknown entries (curve has N = 0, 10, 14) |
| `deck-b-novel.json` | 1 segment (`whole`-like), 10 entries, 4 unmapped tokens, names and particles |

## Format

- `segments[]`: `id`, `ordinal`, `title`, `unmapped_token_count` (rows of `app.media_segment`; the test sets
  `token_count = sum(counts in that segment) + unmapped_token_count`).
- `occurrences[]`: `seq`, `pos_class`, `counts[i]` = `occurrence.count` in segment `i` (0 = no row).
- `word_status`: effective status per seq; a missing seq has no row (= `new`).
- `expected`: the `Coverage` response for the default `hide=ignored`, computed by hand from the contract
  definitions (openapi `Coverage`, `CoverageCounts`); the test asserts exact equality.
  `difficulty` assumes both decks are loaded (they are the only parsed media).
- `jpdb`: the same deck under jpdb's definitions; the test asserts the contract numbers are within 3 points.

## jpdb-style definitions used for `jpdb`

- Unique known % = known (known, never-forget) / distinct entries, blacklisted and ignored entries excluded.
- Token known % = tokens of known entries / tokens of all dictionary-mapped, non-blacklisted entries.
  jpdb has no unmapped tokens, so they are not in its denominator; that is the only difference from the
  contract number here (deck B: 113 / 145 = 77.93 vs 113 / 149 = 75.84).

## Hand computation

Deck A (default hide drops 1008 blacklisted, 1009 ignored → `ignored_count` 2):
- known 1001+1002+1003+1004+1005 = 35+55+20+20+5 = 135 tokens, 5 entries; learning 1006+1007 = 7+6 = 13, 2 entries.
- unknown (14 entries) by tokens: 10, 9, 8, 7, 6, 5, 4, 3 (1017), 3 (1018), 2 (1019), 2 (1020), 1, 1, 1 = 62.
- total 135+13+62 = 210; known_pct 135/210 = 64.2857 → 64.29.
- curve: N=10 adds 57 → 192/210 = 91.43; all 14 add 62 → 197/210 = 93.81.
- segment 0: known 20+30+10+12+5 = 77, learning 4, unknown 6+4+3+6+2+3+1+2+1 = 28 → 109 tokens,
  15 entries (9 unknown), 77/109 = 70.64. Segment 1: known 58, learning 9, unknown 34 → 101 tokens,
  16 entries (10 unknown), 58/101 = 57.43.
- difficulty: 62/210 × 1000 = 295.24 (rank 2 of 2).

Deck B (default hide drops 2008 ignored → `ignored_count` 1):
- known 2001+2002+2003+2009 = 40+50+20+3 = 113, 4 entries; learning 2004 = 8; unknown 2005+2006+2007+2010
  = 10+7+5+2 = 24, 4 entries; unmapped 4.
- total 113+8+24+4 = 149; known_pct 113/149 = 75.84; curve N=0 75.84, N=4 137/149 = 91.95.
- difficulty: 24/149 × 1000 = 161.07 (rank 1 of 2).
