# jpdb/ — review export sample

**Derived from public format docs, unverified against a real export.** `fixtures/private/jpdb-export.json` was absent, so this is an invented, anonymised sample of jpdb's review-history JSON (settings -> export reviews).

- Origin: hand-written. vids, timestamps (Unix seconds) and histories are fictional.
- Licence: CC0.
- Shape used: `cards_vocabulary_jp_en` / `cards_vocabulary_en_jp` arrays of `{vid, spelling, reading, reviews[{timestamp, grade, from_anki}]}`; grades `nothing|something|hard|okay|easy`. `vocabulary_status` (never-forget / blacklisted) is a guess at how known/ignored words appear. Includes edge cases: empty history, Anki-imported reviews, same-hour repeats, a card in both directions. Importer tickets must tolerate shape drift.
