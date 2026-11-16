# SHELL-L9 — Dictionary gap fill: no review card or word page without its entry

- Phase: SHELL · Model: opus · Risk: med
- Deps: SHELL-A2, SHELL-P2
- Branch: t/SHELL-L9-dict-gapfill
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/dict/** (new files + reader wiring), android/src/main/kotlin/kotobaya/android/sync/content/** (post-sync hook only), android/src/main/sqldelight/** (one new cache table), android/src/test/**/dict/**
- Module(s) for CI: android · Gradle-heavy: yes

## Why (user report 2026-11-16)
Even with an up-to-date dictionary, some cards on the phone have no dictionary info — mostly words from the
conversation-focused list. Likely cause: the device installs the `top60k` scope (contracts/db/DICT_PACKAGE.md §2.3),
and list words outside the top 60k are simply not in the package. Today a local miss → `CardUnavailable`
(InstalledRenderData). The previous follow-up AND-DICT-FALLBACK (FOLLOWUPS.md, shelved) is superseded by this.
First: confirm the cause (count local notes whose entry_seq / kanji is missing from the installed package on a fixture or
by reasoning from the scope build); say in HANDOFF if the cause is something else and fix that instead.

## Do
- Sidecar cache in the learning DB (not the read-only dictionary file): entry JSON keyed by `seq` and kanji keyed by
  literal, with the dict release id it came from.
- After every successful sync (ContentInstaller / sync hook): find entry_seqs and kanji referenced by local notes, deck
  items and queue-relevant cards that are missing from the installed dictionary and the cache; fetch them from the server
  (`getDictEntry` / `getKanji`, batched and rate-limited; ≤ a few hundred per run, the rest next run); store them.
  Drop cache rows when a newer installed package now contains them or the release changes.
- One lookup seam used by review rendering (InstalledRenderData / BackExtras) and the local API dictionary reads (SHELL-L5,
  SHELL-L2): installed dictionary → sidecar cache → (online only, local API dictionary reads only) proxy → null.
  Review rendering never blocks on the network.
- Expose the counts (cached, still missing) for the device-settings dictionary row (SHELL-A7 / W2 show them).

## Acceptance
- [ ] Test: a note whose entry is absent from a fixture top60k package renders with full entry data after one gap-fill
      run (fake server), and offline afterwards.
- [ ] Test: nothing referenced is silently dropped — after a run, every referenced seq is either installed, cached, or
      reported as missing with a reason (server 404 / budget).
- [ ] Test: cache row removed when the installed package gains the entry.
- [ ] make check MODULE=android green.

## Out of scope
Changing the default scope (user picks in device settings, SHELL-A7/W2). Server or worker changes.

## Orchestrator note (from SHELL-P2)
- InstalledDictionary needs Android SQLiteDatabase; the parity harness is plain JVM (JDBC). Build the lookup seam so it runs on a JDBC driver in tests (DictStore in ParityDevice), and persist the last known server dict release in device state (SHELL.md L-7 authority rule; the harness uses ParityDictionary.releaseId).
- Register any new handler services in the test ParityDevice (android/src/test/.../shell/parity/) — also owned.
