# F-17 HANDOFF — DONE
`listEntryExamples` implemented (server/.../dict/EntryExamples.kt, registered in `dictHandlers`); removed from WiringAuditTest 501 allowlist.
- tatoeba: `ref.example_sentence_entry` -> `ref.example_sentence`, shortest text then id, `limit` 1..50 (default 10). Empty table -> 200 `{media:[],tatoeba:[]}`. Unknown seq 404, bad limit 422.
- media (also in the contract): sentences via `occurrence.sentence_ids`, live media, fewest unknowns, newest media, id; includes grammar_spans. `unknown_count` uses the stored column (0 if null), not recomputed per filter.
- Tests: EntryExamplesTest (linked, limit, empty, 404, media). `make check MODULE=server` ran with no failure output (quiet `-q` run).

## Human: add Tatoeba to DICT_SOURCES_JSON
Optional key `tatoeba`, sha256 mandatory (worker rejects without it):
`"tatoeba": {"url": "<URL>", "sha256": "<64 hex>"}`
- Worker saves it as `tatoeba.tsv` and expects a TSV of Japanese/English pairs (see worker/.../dict/extra/tatoeba.py). Candidate URL: a Tatoeba-derived JA-EN pair export, e.g. https://downloads.tatoeba.org/exports/ (per-language links) or the Tanaka-corpus-style `jpn_indices`-joined TSV; I could not verify the exact file/format offline.
- Pin: placeholder `"sha256": "REPLACE_WITH_sha256sum_OF_DOWNLOADED_FILE"`; download once, run `sha256sum`, paste.
- Then run dict-refresh. No Tatoeba loaded = empty list, not an error.
