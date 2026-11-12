# CLAUDE.md — Rules for every agent working in this repo

Kotobaya is a self-hosted, local-first Japanese SRS. Spec: `docs/SPEC.md`. Decisions: `docs/DECISIONS.md`.
If you are the orchestrator, read `ORCHESTRATOR.md` instead and follow it.

## Read only what your ticket names
Your prompt lists the ticket, the spec sections, the contracts, and your owned paths. Read those.
Do not explore the rest of the repo unless the ticket requires it; ask the question in HANDOFF.md
instead. Never read `fixtures/private/` unless your ticket says so.

## Module map
| Path | What | Language / tooling |
|---|---|---|
| `contracts/` | Frozen interfaces: `db/`, `api/`, `sync/`, `core/` | SQL, OpenAPI YAML, JSON Schema, Kotlin |
| `core/` | Kotlin Multiplatform shared logic (jvm, android, js) | Kotlin, kotlin.test |
| `server/` | Ktor API | Kotlin, JUnit 5, Testcontainers |
| `worker/` | Python jobs: dictionary, import, parse, coverage, TTS, optimizer | Python 3.12, uv, pytest, ruff, mypy --strict |
| `web/` | SvelteKit static SPA | TypeScript strict, vitest |
| `android/` | Android app | Kotlin, Compose, SQLDelight, Robolectric, instrumented tests |
| `clients/` | Generated API clients — never hand-edit | — |
| `deploy/` | Helm chart, k3d scripts, backup | Helm, bash |
| `acceptance/` | Offline/sync chaos simulation (T313) | Kotlin |
| `fixtures/` | Test data (see each README for licence) | — |
| `plan/` | Orchestrator's board — writers never edit | Markdown |

## Commands
- `make check MODULE=<core|server|worker|web|android|deploy|contracts>` — build + tests for one module
  (`contracts` regenerates clients/schemas and fails on drift).
- `make test` — all modules. `make fmt` — format everything (run it; no lint gate).
- `make dev-up` / `make dev-down` — compose stack.
Run your module's `make check` before you hand off. It must be green.

## Tests that must exist (lean mode — everything else is optional)
Personal project: no CI pipeline, no lint gates, no e2e browser tests, no perf targets. Write
tests only where a bug would silently corrupt or lose data, or where the ticket's acceptance says so:
- FSRS: golden-vector tests.
- Sync and replay: the worked examples from `contracts/sync/SYNC.md`, plus the convergence fuzz test.
- Op apply: idempotency and out-of-order handling.
- Auth: scope enforcement and token revocation.
- Importers (jpdb history, media): mapping counts on fixtures; nothing silently dropped.
- Parser and coverage: the fixture agreement tests.
UI code needs no tests unless the ticket says so.

## Hard rules
1. **Edit only your owned paths.** Lockfiles and `HANDOFF.md` are always allowed.
2. **Never edit `contracts/`** unless you are the architect on a contract ticket. If a contract is
   wrong or missing something, stop and return `CONTRACT_CHANGE_REQUEST` (see handoff template).
3. **Never hand-edit `clients/`** — regenerate.
4. **Tests where they matter.** See "Tests that must exist". UI and glue code may ship untested.
5. **No new dependencies** without a line in HANDOFF.md saying why; prefer what's already used.
6. Every user-visible string in the Android and web apps goes through the i18n layer (English only for now).
7. Secrets only via env/Kubernetes Secrets. Never commit `.env.local` or anything in `fixtures/private/`.
8. All learning-data mutations go through sync ops (`contracts/sync`). No direct table writes from
   API handlers except inside the op-apply path.
9. Times are UTC instants in storage; the user's time zone matters only for day boundaries.
10. If blocked for more than two attempts at the same problem, stop and return `BLOCKED` with the
    specific question. Don't thrash.

## Style
- Kotlin: official style, explicit API mode in `core`, no `!!` outside tests, coroutines over threads.
- Python: typed, dataclasses or pydantic for records, no global state, pure functions where possible.
- TypeScript: strict, no `any`, components small, data via the generated client only.
- Commits: `<ID>: <imperative summary>`; one logical change per commit.

## Handoff
Finish by writing `HANDOFF.md` at the worktree root from `plan/templates/handoff.md`, commit, and
reply with its contents only. Keep it under 200 words. The orchestrator reads only this.
