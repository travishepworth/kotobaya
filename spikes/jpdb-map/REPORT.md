# S02 report: jpdb deck fetch, JMdict mapping, export structure

Code: `jpdbmap.py` (map / stats / deck-api / deck-page), `structure.py`, `deeper.py` (aggregate-only), `test_jpdbmap.py` (`python3 -m pytest` in this dir; stdlib only). Run 2026-11-12.

## Headline
- **jpdb `vid` is the JMdict `ent_seq`** for ordinary words (live page: 49/50 vids are the mapped seq; export: 6915/6922 rows have vid in JMdict and agree with spelling/reading). Mapping is a lookup, not a search.
- Hit rate on the real export vs full JMdict (219,058 entries, cached): **99.93%** (6917/6922), 0 ambiguous. **Above the 95% gate; GATE-S02 not triggered.**
- Live deck page (public, no login): 50/50 entries mapped, 0 unmapped.

## Mapping
Order: (1) vid is a JMdict seq and agrees with spelling+reading, spelling, or kana reading; (2) exact (spelling, reading) pair; (3) kana-only word to reading, preferring `uk` entries; (4) spelling only. Text normalisation: NFKC + katakana->hiragana.
Export (6922 rows): vid confirmed by text 6036, vid kana 879, text-only fallback 2, unmapped 5 (3 have kanji, 2 kana-only; one has vid >= 2e9, i.e. outside the JMdict range, probably a jpdb custom/name entry). Unmapped samples from private data are not listed (privacy); the review queue handles them.
Public-page sample (anime deck, page 1): 23 spelling+reading, 26 kana, 1 fallback; no unmapped.
**Gotcha:** vid is NOT unique per export row: 260 vids occur in 2-3 rows with different spelling/reading and different review lists (one JMdict entry, several written forms). T207 must key notes by seq and merge/choose among those histories (merge by timestamp is safe since timestamps do not collide).

## Deck fetch
- **Page fallback works unauthenticated**: `/prebuilt_decks` lists decks; `<deck>/vocabulary-list` (50 entries/page, `?offset=N` via "Next page" link; `sort_by=by-frequency-global|local`). Per entry: vid in the link, spelling in the URL slug, reading from `<rt>` furigana (absent for kana-only words), global rank as a "Top N" tag (a bucketed rank, e.g. Top 100, Top 5300, not an exact rank, and may be missing), per-deck occurrence count as the trailing number. Parser: `parse_vocab_list_html`. HTML selectors are scraped, so fragile; cache + fixture-test them.
- Rate limit >= 2 s between network calls; responses cached on disk at `~/.cache/kotobaya-jpdb` (override `KOTOBAYA_JPDB_CACHE`); the API key is never part of a cache key or file.
- **API path** (`api_call`, `deck_vocabulary`): jpdb API v1, `Authorization: Bearer $JPDB_API_KEY`, POST JSON. Tested with a mocked opener only. The exact endpoint names/request fields (`deck/list-vocabulary`, `fetch_occurences`) and the response column layout are **unverified** (no key); confirm in the live run below.

## Export structure (private file; structure only)
Top level: one object, 4 array keys, no metadata/version/status keys.
| key | rows | reviews | row fields |
|---|---|---|---|
| `cards_vocabulary_jp_en` | 6922 | 62,366 | `vid` int, `spelling` str, `reading` str, `reviews[]` |
| `cards_vocabulary_en_jp` | 0 | - | (empty) |
| `cards_kanji_char_keyword` | 2185 | 22,979 | `character` str(1), `reviews[]` |
| `cards_kanji_keyword_char` | 754 | 3,061 | `character` str(1), `reviews[]` |

Review object (all three card types): `timestamp` int Unix seconds UTC (2026-02-04 .. 2026-11-12), `grade` str, `from_anki` bool (always false here). Reviews per card 1..47, ascending by timestamp, no duplicate timestamps. `vid` ranges 1,000,225 .. 2,147,656,918 (one row >= 2e9; all others <= 9,000,036). 882 vocab rows have spelling == reading (kana words).
Grade enum: `nothing, something, hard, okay, easy` (the five jpdb ratings) plus **`known`, `unknown`** (status events) and, vocab only, `pass`, `fail` (5 cards, 1 `fail`/4 `pass` events in total; rare, probably an old/other review type).
- `unknown` (5617) and `known` (1304) occur only as the **first event** of a card: `unknown` = card added as new (then reviews follow), `known` = marked already-known (65 cards end on `known`, i.e. no reviews, 86 single-event cards). Never mid-history.
- Grade totals, vocab: unknown 5617, something 7122, hard 25449, okay 14331, easy 8405, known 1304, nothing 133, pass 4, fail 1. Note `hard` dominates: jpdb's "hard" is a pass-ish rating, not FSRS Hard-as-fail; mapping table needed in T207.
- No sid, no occurrence counts, no `vocabulary_status` section; **never-forget and blacklist are not in this export** (blacklisted/never-forget words presumably need the API, or the export simply has none for this account).

## Fixture diff (`fixtures/jpdb/reviews-sample.json` vs real)
1. Fixture has `_note` and `vocabulary_status`; real has neither.
2. Real has two kanji arrays (`cards_kanji_char_keyword`, `cards_kanji_keyword_char`) absent from the fixture; `cards_vocabulary_en_jp` exists but empty (fixture populates it for a "both directions" case).
3. Grades: fixture has only the 5 ratings; real adds `known`, `unknown` (first-event status markers) and `pass`/`fail`.
4. `from_anki` is always false in real (fixture has true cases; keep the field tolerant).
5. Vids: fixture 1000001+ unique; real vids are JMdict seqs, can repeat across rows with different spelling/reading, and one is >= 2e9.
6. Fixture has an empty-history card; real minimum is 1 event.
7. Timestamps: same format (int seconds); real range is 2026 only. Same-hour repeats exist in the fixture; none exact-duplicate in real.
8. Scale: 6922 vocab rows / 62k events vs 6.

## Live API run for the human (needs key)
```
export JPDB_API_KEY=...   # from jpdb settings; do not commit
cd spikes/jpdb-map
python3 jpdbmap.py deck-api <deck_id> --out /tmp/deck.json   # then inspect the raw response shape; paste field names back
```
Also useful: confirm whether the API exposes never-forget / blacklist lists.
