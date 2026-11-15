# CLI daemon — desktop widgets and keyboard reviews

Status: design agreed with the human 2026-11-15. Source for tickets ARCH-CARD-TEXT, CLI-DAEMON,
CLI-REVIEW, CLI-WATCH. Builds on T604 (`cli/`, Python `kotobaya`).

## Goal
Show review state in desktop widgets (waybar, eww, ags, quickshell, tmux, i3blocks) as a JSON
stream, and do reviews from keybindings / widget clicks with no perceptible latency. Scope is
**review only**; other actions stay one-shot CLI commands.

## Measured problem
`kotobaya --help` takes ~510 ms; importing the generated `kotobaya_client` alone is ~510 ms; bare
`python -c pass` is 15 ms. Per-call Python cannot meet the goal.

## Design

```
binding / on-click ──echo good 1<>$XDG_RUNTIME_DIR/kotobaya/in──┐   (shell builtin, ~0 ms)
                                                               ▼
        kotobaya daemon  (Python, systemd --user, generated client loaded once)
          owns: queue prefix (~20), counts, current card, revealed, shown_at,
                last review id, conn state
          in:   FIFO  $XDG_RUNTIME_DIR/kotobaya/in   (fire-and-forget actions)
                unix socket $XDG_RUNTIME_DIR/kotobaya/sock  (request/reply + subscribe)
          net:  keep-alive HTTP + SSE GET /events
          disk: answer journal $XDG_STATE_HOME/kotobaya/journal
                                                               │ NDJSON: full state per line
                                                               ▼
        kotobaya watch [--format json|waybar|'<template>']   (long-lived)
                                                               ▼
                                   waybar / eww / ags / tmux …
```

Principles:
- Python runs only in long-lived processes (daemon, `watch`). No Python on the keypress path.
- Actions never need a reply: the result is the next line on the stream the widget already reads.
- Every stream line is the **full state**, never a delta. Widgets are stateless; multiple widgets
  stay in sync.
- The daemon is the single source of truth for session state. No state files besides the journal.

### Inputs
FIFO: one command per line (each < PIPE_BUF, so concurrent writers are atomic). The daemon opens
the FIFO O_RDWR so it never sees EOF; writers use `1<>` so they never block if the daemon is down
(the keypress is dropped; the widget already shows `offline`).

Commands: `reveal`, `again`, `hard`, `good`, `easy`, `nothing`, `something`, `okay` (five-button
names; the daemon reads the user's button mode from `GET /settings` and maps per the
`/review/answer` contract, always sending `button`), `undo`, `skip` (moves the current card behind
the prefetched ones, no server call), `refresh`. Unknown/invalid commands emit a `notice`, never crash.

Socket: newline-delimited JSON request/reply: `{"cmd":"status"}`, `{"cmd":"next"}`,
`{"cmd":"answer","rating":"good"}`, `{"cmd":"reveal"}`, `{"cmd":"undo"}`, and
`{"cmd":"subscribe"}` which keeps the connection open and streams state lines.

### State object (v1, JSON Schema in `cli/schema/state.v1.json`)
```json
{"v":1,"conn":"ok|offline|auth","counts":{"new":8,"learning":3,"review":41},
 "day_ends_at":"…","card":{"id":"…","kind":"vocab","front":"…","back":"…","reading":"…",
 "sentence":"…","revealed":false,
 "previews":{"again":"10m","hard":"1d","good":"3d","easy":"8d"}},
 "last":{"rating":"good","undoable":true},"pending":0,"notice":null}
```
`card` is null when the day is done. `pending` is the number of unsent journal entries. `back`
is always present; widgets decide what to show from `revealed`.

### Review flow
1. **Answer = journal first.** Mint the UUIDv7 `id`, write `{id, card_id, rating, button,
   reviewed_at, duration_ms, utc_offset_min}` to the journal and fsync, then advance to the
   prefetched next card and emit state. The POST `/review/answer` and the queue refetch run in
   the background.
2. Retries (timeout, 5xx, daemon restart) resend the **same** journal entry; the server's `id`
   dedupe makes them idempotent. Journal entries are removed only after a 2xx (or a terminal
   409/410/422, which emits a `notice`). Replay on daemon start.
3. **The background refetch never swaps the card on screen**; it only replaces counts and the
   cards after it. Exception: `sync.changed` → refetch shows the current card is no longer in the
   queue (reviewed elsewhere, suspended, deleted) → replace it, `notice:"reviewed elsewhere"`.
4. **No offline reviewing** in v1. Unreachable server → `conn:"offline"`, no new card served;
   answers already journaled are replayed on reconnect. Token revoked/401 → `conn:"auth"`.
5. `duration_ms` = answer time − max(shown_at, last widget-visible action), capped at 60 s
   client-side (server clamps at 600 s).
6. `undo` posts `/review/undo` with the last answered id (journal-first as well), puts that card
   back at the head unrevealed.
7. Day boundary (`day_ends_at`) and a 60 s timer trigger a refetch (learning cards coming due).

### Card content
Rendering is core's job. **ARCH-CARD-TEXT** adds a plain-text render to `QueueItem`
(`text: {front, back, reading?, sentence?}`), produced on the server by core for every card kind.
Until it lands, the daemon ships counts/conn only (`card` fields other than `id`/`kind` absent).
Do not re-implement rendering in Python.

### Config and process
- `~/.config/kotobaya/config.toml` (`tomllib`): `url`, `token_file` (or `token_cmd`). Env
  `KOTOBAYA_URL` / `KOTOBAYA_TOKEN` override. waybar/systemd don't inherit the shell env.
- Docs recommend a PAT with scopes `read` + `review` only.
- `cli/systemd/kotobaya.service` (user unit, `Restart=always`).
- `watch` reconnects in a loop with backoff, emitting `conn:"offline"` while the daemon is down.
- Existing one-shot commands (`health`, `cards find`, `media import`, `jobs`) are unchanged.

## Rejected
Go/Rust binary (actions are already ~0 ms); stdlib rewrite of the client (daemon loads it once);
local replica + core FSRS on desktop (duplicates the Android sync stack); optimistic swap to the
server's new head (jarring; server accepts any order); socket replies for actions (stream carries
the result).

## Tickets
| ID | Agent | Risk | Paths | Deps |
|---|---|---|---|---|
| ARCH-CARD-TEXT | architect | medium | contracts/api, core, server | — |
| CLI-DAEMON | writer | medium | cli/ | T604 |
| CLI-REVIEW | writer-senior | medium | cli/ | CLI-DAEMON (card text needs ARCH-CARD-TEXT) |
| CLI-WATCH | writer-light | low | cli/ | CLI-DAEMON |

Required tests (CLI-REVIEW): journal written before POST; retry/replay reuses the same id (no
double `review.logged`); daemon crash between journal and POST replays exactly once; terminal
4xx drops the entry with a notice; current card replaced only when gone from the queue; undo
targets the last answered id. CLI-DAEMON: FIFO parsing (invalid input never crashes),
full-state-per-line invariant. CLI-WATCH: no tests required; ship example waybar, eww and
Hyprland configs in `cli/README.md`.
