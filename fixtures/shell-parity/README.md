# fixtures/shell-parity

Goldens for the server-versus-shell parity scenarios (`contracts/api/shell/SHELL.md` section 8).

- `golden/<id>.json` — the server's normalised answers to `contracts/api/shell/parity/<id>.json`:
  `{id, steps: [{status, headers, body} | null]}` (`null` for apply steps), normalised per SHELL.md 8.3.
- Recorded by SHELL-P1 (`server/src/test/kotlin/kotobaya/parity/ParityGoldenTest.kt`):
  `RECORD=1 ./gradlew :server:test --tests 'kotobaya.parity.*'`. Without `RECORD=1` the same test
  requires the server to reproduce every golden. Recording fails when an answer contradicts a
  scenario's `expect` or `invariants`.
- Consumed by SHELL-P2 (the Android `LocalApi` must reproduce them).

Licence: generated from the project's own scenario files and the `fixtures/dict` subset (see
`fixtures/dict/README.md` for the dictionary data's licence).
