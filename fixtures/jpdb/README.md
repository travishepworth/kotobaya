# jpdb/ — review export sample

**Structure matches the real export as observed in the S02 spike (`spikes/jpdb-map/REPORT.md`, "Export structure" and "Fixture diff"), but all values are invented.** No private export was used; `fixtures/private/` was not read.

- Origin: hand-written. vids (JMdict-style seqs), spellings, timestamps (Unix seconds, 2026) and histories are fictional.
- Licence: CC0.
- Top level: exactly four arrays, no metadata keys: `cards_vocabulary_jp_en`, `cards_vocabulary_en_jp` (empty, as in the real export), `cards_kanji_char_keyword`, `cards_kanji_keyword_char`.
- Vocab row: `{vid, spelling, reading, reviews[]}`. Kanji row: `{character, reviews[]}`.
- Review: `{timestamp, grade, from_anki}`. `from_anki` is always false in the real export; keep it as a tolerant bool.
- Grades: `nothing|something|hard|okay|easy`, plus `known` / `unknown` (status events, only ever the first event of a card; `known` may be the only event) and `pass` / `fail` (rare, vocab only).
- Edge cases included: one vid used by two rows with different written forms (`学校` / `がっこう`), one vid >= 2e9 (outside the JMdict range), a card whose only event is `known`, kana-only words (spelling == reading), a `fail` event.
- Not present in the real export and therefore not in the sample: `vocabulary_status` / never-forget / blacklist (not in the export; needs the API), sids, occurrence counts.
- Importer tickets must tolerate shape drift.
