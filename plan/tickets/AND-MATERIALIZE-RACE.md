# AND-MATERIALIZE-RACE — Can two concurrent queue fetches materialize one entry twice?

- Model: opus (writer-senior) · Risk: high (data: duplicate notes) · Gradle: yes
- Owned: android/src/main/kotlin/kotobaya/android/notes/** (materialize path), android/src/main/kotlin/kotobaya/android/shell/api/review/** (materializer wiring), android/src/main/kotlin/kotobaya/android/sync/SqlDelightDeviceStore.kt (transaction mode only), android/src/test/**
## Evidence
`QueueMaterializeIdempotentTest.twoConcurrentFetchesCreateOneNotePerDrawnEntry` failed once ("live notes of 1000220 expected:<1> but was:<2>") during the SHELL-Z1 check; 6/6 passes on main and Z1 after. The SHELL-L2 review noted the test runs both fetches on ONE shared in-memory connection, so it cannot prove isolation; correctness relies on NoteCreator.materialize re-running DuplicateCheck.byEntry inside store.transaction.
## Do
1. Determine whether production can duplicate: is the store transaction BEGIN IMMEDIATE/EXCLUSIVE (writer lock taken before the duplicate check) on the real Android driver (WAL)? If deferred, two transactions can both read "no duplicate". Fix in production if so (immediate transaction or a process-wide materialize lock; the shell LocalWrites lock may already cover it — prove it).
2. Make the test faithful: two real connections to one file DB (or the production driver), many iterations; it must fail on the unfixed path if a race exists, and pass deterministically after.
3. Also cover the native Takoboto/AnkiProvider add racing a queue materialize for the same entry.
## Acceptance
- [ ] HANDOFF states clearly: real bug or test artifact, with evidence.
- [ ] Test runs 50× green; make check MODULE=android green.
