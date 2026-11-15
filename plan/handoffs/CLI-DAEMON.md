# HANDOFF CLI-DAEMON

STATUS: DONE
BRANCH: t/CLI-DAEMON
CHECK: `make check MODULE=cli` → green (45 pytest, mypy --strict, ruff)

## Changed (≤ 6 lines)
- `cli/src/kotobaya_cli/daemon/`: `protocol` (FIFO/socket parsing, button mapping), `session` (state, queue rules), `backend` (queue/settings/SSE on generated client), `server` (asyncio FIFO+socket+SSE thread+60 s timer).
- `config.py` (env > config.toml, token_file/token_cmd); `kotobaya daemon [--config]`; `schema/state.v1.json`; `systemd/kotobaya.service`.
- Seam for CLI-REVIEW: `AnswerHandler` protocol in `server.py` (answer/undo/on_online/pending_count; default `NoAnswers` emits a notice), `Session.pop_current/push_front/duration_ms/last`, `Paths.journal`, `resolve_button`.

## Acceptance → tests
- FIFO parsing never crashes, emits notice → `test_fifo_parsing_*`, `test_every_emitted_line_is_a_full_valid_state_and_junk_never_crashes`
- Full state per line vs schema → same integration test (real FIFO+socket) + `test_offline_and_auth_*`
- Config precedence, token_file/cmd → `tests/test_config.py`

## New dependencies
- jsonschema (dev only): validates emitted lines against state.v1.json.

## For the reviewer (≤ 3 lines)
- Decisions: card carries `revealed` and `previews` (from existing contract) now; text fields absent until ARCH-CARD-TEXT (backend reads `item.text` via getattr). Socket `next` = skip; `refresh` never swaps the card. `conn` offline blocks skip. FIFO is kept on shutdown (a `1<>` writer would otherwise create a regular file; startup replaces one). Notices are one-shot. SSE uses urllib3 directly (not typed in the client).
- Subscribers get a 64-line drop-oldest mailbox; unknown-ish `kind` is passed as the server's card kind (e.g. "recognition"), not "vocab".
