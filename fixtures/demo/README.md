# fixtures/demo — demo dataset for UI work

- Origin: invented by hand for UI design work (UI-FOUND-WEB). Headwords, glosses, readings, sentences, decks,
  stats and ids are made up; nothing is copied from JMdict/KANJIDIC, jpdb or any private data.
- Licence: **CC0 1.0** (public domain dedication).
- Built by `python3 fixtures/demo/build.py` (stdlib only, deterministic). Edit `build.py` and rerun; the JSON
  files are its output and are committed so other tools (web mock server, Android demo mode) need no Python.
- Used by: the web mock-server mode (`make dev-ui`, `web/mock/demo-api.ts`) and the screenshot harness
  (`make screenshots-web`). Android (UI-FOUND-APP) is meant to load the same files.

## Shape

Every object is in **API wire form**: exactly the JSON the server sends for that schema in
`contracts/api/openapi.yaml` (snake_case keys, RFC 3339 instants, ISO dates). So any generated client
(TypeScript `XFromJSON`, the Kotlin client) can parse a value directly. Ids are UUIDv7-shaped and fixed;
dictionary `seq` values live in the made-up 9000001–9000040 range. "Now" for the dataset is
2026-11-13T09:00:00Z (study day 2026-11-13, day ends 2026-11-14T04:00Z); due dates are relative to it.

| File | Keys → schema |
|---|---|
| `account.json` | `principal` → Principal, `meta` → Meta, `settings` → Setting[], `presets` → Preset[], `note_types` → NoteType[] (5 built-ins + 1 custom), `devices` → Device[], `tokens` → Token[] |
| `dictionary.json` | `entries` → DictEntry[], `kanji` → Kanji[], `tatoeba` → { "<seq>": TatoebaSentence[] } |
| `learning.json` | `notes` → Note[] (each with its `cards` → Card[]), `decks` → Deck[], `deck_items` → { "<deck id>": DeckItem[] }, `deck_lists` → DeckListCatalogueEntry[], `hot_words` → HotWord[], `hot_kanji` → HotKanji[], `confusables` → Confusable[] |
| `media.json` | `media` → Media[] (with `segments`), `sentences` → Sentence[], `coverage` → { "<media id>": Coverage }, `parse_overrides` → ParseOverride[], `jpdb_vocab` → JpdbVocab[], `grammar_points` → GrammarPoint[] |
| `stats.json` | `summary` → StatsSummary, `heatmap` → StatsHeatmap, `retention` → StatsRetention, `forecast` → StatsForecast, `known_words` → StatsKnownWords, `calibration` → StatsCalibration |
| `scenarios.json` | `{ "<name>": { description, queue_counts: {new, learning, review}, empty: [keys] } }` |

Derived views (a card's `note` summary, review-queue items, list pages) are not stored; consumers build them
from the above (see `web/mock/demo-api.ts`).

## Scenarios

A scenario is the dataset with the keys in `empty` cleared (arrays → `[]`, maps → `{}`, `stats` → zeroed) and
the review-queue counts from `queue_counts`:

- `default` — a lived-in account: 65 notes / 108 cards, 31 decks (user, smart, list, media), 6 media, a year of
  review history, 53 due.
- `due900` — the same account after a long break: 900 due today.
- `empty` — a brand-new account: built-in note types, the Default preset and settings only.

## Deliberately awkward content

- Long multi-sense glosses: 取る (17 senses), 掛ける (9), 先生, 勉強.
- Rare kanji: 鬱 (29 strokes), 薔薇, 齟齬, 躊躇, 魑魅魍魎, 顰蹙, 檸檬, 蟷螂, 鰯.
- Very long readings: 国際連合教育科学文化機関, 独立行政法人, 東京特許許可局, 寿限無….
- Dictionary-backed vocab notes with every field empty (rendered from the entry), one note with all fields
  empty and no reference, a user gloss overriding the dictionary.
- Kanji notes (`kanji_literal`), grammar, sentence, phrase and a custom note type with a very long name.
- Audio and no audio: some notes and anime sentences carry a (non-existent) blob hash, others none.
- 30+ decks including disabled ones, a very long name, a smart deck with an invalid query, an empty list deck.
- A media item whose parse failed; devices with no last-seen time and a revoked one; an expired token.
