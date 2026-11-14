# HANDOFF C-24

STATUS: DONE
BRANCH: t/C-24-search-dict-forms
CHECK: `make check MODULE=core` green (Postgres executed, not skipped); `contracts` green; `server` green

## Changed (≤ 6 lines)
- Search.kt `SearchTerm.Text`: rule 2, entry-linked notes also match their `dict_entry_form` form / normalised norm
- SearchSqlCompiler: Postgres correlated `EXISTS` on `ref.dict_entry_form`; SQLite `SearchContext.dictForms` lookup (new, default null), seqs bound as one `instr` string
- `normalizeForm` (NFKC expect/actual for jvm/android/js, plus katakana fold); SqlSnapshots regenerated (Postgres only)
- SCHEMA.md 4.5 Notes recipe; ADR-025

## Acceptance → tests
- 食べる / たべる / タベル / ﾀﾍﾞﾙ / Ｔシャツ match empty-field notes, both dialects → SearchSqlExecutionTest (EXPECTED rows + sqliteWithoutInstalledDictionaryMatchesNoteTextOnly)
- other examples unchanged → existing EXPECTED rows, SQLite snapshots byte-identical

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Device: the dictionary is a separate SQLite file, so I used a compile-time lookup callback instead of ATTACH. Android `LocalCardSearch` does not pass it yet (follow-up; until then the device behaves as before).
- `top60k` packages can miss a later-referenced entry. That is a documented device/server difference.
