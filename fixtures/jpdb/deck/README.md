# jpdb/deck — vocabulary-list fixtures

**Hand-written (CC0); structure copied from the S02 spike's description of public jpdb vocabulary-list pages (`spikes/jpdb-map/REPORT.md`, "Deck fetch"). All values invented.** No private data used.

- `deck.html`: deck page, title only.
- `vocabulary-list-{1,2,3}.html`: 4 + 3 + 2 = 9 `entry` rows; pages 1 and 2 have a "Next page" link (`offset=50`, `offset=100`), page 3 does not.
- Edge cases: kana-only rows (no `<rt>`: この, は, ラーメン, でも), rows without a "Top N" tag (この, でも), okurigana outside `<ruby>` (食べる), one malformed row on page 2 (no vocabulary link; must land in `skipped`).
- `api-response.json`: mocked API response in the *assumed* layout (real field names unverified; see T401a HANDOFF).
