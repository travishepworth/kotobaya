# SHELL-L5 — Local dictionary and mining: dictionary reads, createNote

- Phase: SHELL · Model: opus · Risk: high
- Deps: SHELL-L1, SHELL-P2, SHELL-L9
- Branch: t/SHELL-L5-local-mining
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/mining/**, android/src/test/kotlin/kotobaya/android/shell/api/mining/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 5, 6
- Contracts: openapi.yaml (searchDictionary, getDictEntry, listDictHeadwords, getKanji, listKanjiWords, listEntryExamples, getDictRelease, createNote), contracts/db/DICT_PACKAGE.md, contracts/sync/OPS.md (note.created, card.created), SYNC.md 8.1
- Decisions: ADR-011, 026, 035, 036

## Do
- Dictionary reads are `local_or_proxy` (L-7): `InstalledDictionary`, `WordSearch.local`, `WordRepository`, `EntryLabelCache`; kana/kanji/romaji
  queries, cursor paging and ordering as the contract; `in_my_cards`-style flags from the replica. Not installed → `Fallthrough`.
- `listEntryExamples`: sentences of installed media only (may be empty offline; documented in SHELL.md).
- `createNote`: `NoteCreator.create` (duplicate check, entry match, add_kanji notes, deck placement); card kinds from core `CardGenerator`
  / `CardKinds.isRetired` (no reading card while `cards.reading_enabled` is not true); client `id` idempotency (existing → 200, nothing minted);
  duplicate → the server's status and problem code.

## Acceptance (each bullet → at least one named test)
- [ ] Parity scenarios green: createNote duplicate, same-id retry, reading retired; dictionary search for kana, kanji, romaji input
- [ ] `CreateNoteOpsTest`: ops validate against op.schema.json; one `note.created` + the expected `card.created`s in one batch; retry mints nothing
- [ ] `SameWordTwiceOfflineTest`: the SYNC.md 8.1 worked example — two devices add the same entry offline → one survivor with merged cards/reviews after both sync
- [ ] `DictFallthroughTest`: no dictionary + offline → 503 shell_dict_missing; no dictionary + online → proxied

## Out of scope
Takoboto/AnkiConnect paths (unchanged, already use NoteCreator).

## Notes from previous attempts

## Orchestrator note (2026-11-16)
Dictionary reads go through the SHELL-L9 lookup seam (installed → sidecar cache → proxy when online). Never return a null entry while a cached one exists.

## Orchestrator note (from SHELL-A2)
Also owned: delete your ops' lines from PENDING in the android RouteTableTest (shell/api tests). Routes are top-level vals in shell/api/<group>/Routes.kt; get deps via call.services.

## Orchestrator note (from SHELL-P2)
- InstalledDictionary needs Android SQLiteDatabase; the parity harness is plain JVM (JDBC). Build the lookup seam so it runs on a JDBC driver in tests (DictStore in ParityDevice), and persist the last known server dict release in device state (SHELL.md L-7 authority rule; the harness uses ParityDictionary.releaseId).
- Register any new handler services in the test ParityDevice (android/src/test/.../shell/parity/) — also owned.

## Orchestrator note (from SHELL-L9)
DictLookup (dict/DictLookup.kt) implements L-7 steps 1-4 + authority; use it, do not re-implement. In plain-JVM parity, JdbcPackageSource detail reads throw (org.json stubs only); if dictionary-detail scenarios need it, make it work there (owned: the parity test dir).
