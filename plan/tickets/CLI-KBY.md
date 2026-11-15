# CLI-KBY — Machine interface for GUI widgets: daemon grade gating + hidden-answer state; `kby serve` / `kby do`

- Phase: P9 · Model: sonnet · Risk: med
- Deps: CLI-DAEMON, CLI-REVIEW, CLI-WATCH (all MERGED); server 0.1.16 (QueueItem.text) is live
- Branch: t/CLI-KBY
- Owned paths (only edit these): cli/ (incl. new cli/bin/kby), cli/README.md
- Module(s) for CI: worker-style python check for cli (whatever `make check` target covers cli/; if none, run cli's pytest/ruff/mypy as CLI-DAEMON did and say so in HANDOFF)

## Read
- cli/README.md, cli/src/kotobaya_cli/daemon/{session.py,backend.py,protocol.py}, `kotobaya watch` implementation
- contracts/api/openapi.yaml `CardText` (which fields are answer-side) and `QueueItem.text`
- docs/CLI-DAEMON.md

## Starting point for kby (user decision, 2026-11-15)
`kby` does not exist in the repo yet. Start `cli/bin/kby` as an exact copy of the user's installed script
`/home/travmonkey/.local/bin/kby` (351 lines; has `kby -f`/stream, widget confs in
`~/.config/kotobaya/widgets/NAME.conf`, `kby click`, `-1..-4 -r -u -s -R -n`). Commit that copy first
("CLI-KBY: import installed kby as-is") so the diff of your changes is reviewable. An earlier
uncommitted 103-line draft was scrapped by the user; ignore it.
Do NOT modify `~/.local/bin/kby` or the user's widget confs; the user installs from the repo afterwards.

## Spec (user's text, verbatim; some lines were truncated in transit — marked […]; infer the obvious intent)

Context: kby is a bash wrapper around the kotobaya CLI/daemon. A GUI widget needs to drive review
sessions through it: it shows the current card, a reveal button, four grade buttons, and small
undo/skip/refresh buttons, and its display changes with the review state. Add a clean machine
interface for that, keeping everything that exists today unchanged (`kby -f`, widget confs,
`kby -1..-4 -r -u -s -R -n`, `kby click`).

### PART A: minimal kotobaya changes (daemon / CLI)
A1. Grade only a revealed card. daemon/session.py: again/hard/good/easy are refused unless the
    current card is revealed (only `reveal` checks state today). Reply with an error/notice, change
    nothing. [Alternative or addition: grade commands take a card id and are refused on a mismatch.]
A2. Never publish the answer while the card is hidden. `_card_dict` currently includes
    front/back/reading/sentence whenever the server sent text, revealed or not. While hidden,
    publish only id, kind, front, revealed and previews. Add back, sentence (and reading, if the
    deck treats it as answer-side) only after reveal.
A3. (If cheap) Expose the next-interval hints for each grade on a revealed card (e.g. again "1m",
    hard "6m", good "1d", easy "4d"), as fields in the published state. Skip if not on hand; the
    client treats them as optional. [Orchestrator note: the daemon already publishes `previews`
    {again,hard,good,easy} — reuse them.]
Nothing else in kotobaya needs to change. `kotobaya watch` stays the source of state.

### PART B: the kby script
B1. `kby serve`: a long-running stream
  - Wrap `kotobaya watch`. For every daemon state change, print exactly ONE line of compact JSON to
    stdout and flush it at once (jq -c; use stdbuf/--unbuffered so lines aren't held in a pipe buffer).
  - Print a line straight away at startup: state "loading" until the first daemon state.
  - Don't read widget confs and don't run […]th is data only.
  - Line shape, version 1:
    {
      "v": 1,
      "state": "loading" | "front" | "bac[k" | "done" | "offline" | "auth" | "error"  — inferred from the actions table],
      "rev": "<card_id>:<n>",
      "text": "語 12",
      "counts": { "new": 0, "learning": 0[, "review": 0] },
      "card": null | {
        "id": "...", "kind": "...",
        "front": "...",
        "reading": "...",        // revealed only, if the deck treats it as answer-side
        "sentence": "...",       // revea[led only]
        "back": "..."            // revealed only
      },
      "last":   { "rating": null | "again" | "hard" | "good" | "easy", "undoable": false },
      "notice": null | "...",
      "error":  null | "...",
      "actions": {
        "reveal":  { "enabled": true },
        "again":   { "enabled": false, "h[int": "1m"] },
        "hard":    { "enabled": false, "hint": "6m" },
        "good":    { "enabled": false, "h[int": "1d"] },
        "easy":    { "enabled": false, "hint": "4d" },
        "undo":    { "enabled": false },
        "skip":    { "enabled": true },
        "refresh": { "enabled": true },
        "next":    { "enabled": false }
      }
    }
  - Field rules:
    * `rev` changes on EVERY line: the ca[rd id plus a counter that gro]ws per line. Clients send it
      back with each action so a stale click can be dropped.
    * Use real JSON types: booleans and numbers, never "true"/"1" strings. Use null for "none", never "".
    * Leave answer-side card fields OUT o[n front lines]. Don't send them empty.
    * `text`: a short one-line summary fo[r a bar: an] icon or the front, plus the due count).
    * `notice` is one-shot: it appears on[ one line and is gone on the] next.
    * `hint` keys appear only when kotoba[ya has them; client]s treat them as optional.
  - Which actions are enabled in each sta[te]:
      loading / offline / auth / error : refresh
      front : reveal, skip, refresh, undo
      back  : again, hard, good, easy, skip, refresh, undo (if undoable)
      done  : refresh, undo (if undoable) […pulled)]
    [Orchestrator reading: `next` = reveal-if-hidden-else-good, as `kby -n`; enable it where reveal or good is enabled. "undo" on front: only if undoable.]
  - Errors never end the stream. If kotoba[ya/the dae]mon is unreachable, auth fails or the watch
    dies: print a [line with state offline/auth/error] and a readable `error`, then retry with
    backoff (1s, […]) [until it re]covers, carry on with normal lines.
  - Keep writing each line to $XDG_RUNTIME_DIR/kotobaya/state.json, as `-f` does (write a temp file
    in the same director[y and rename, so readers neve]r see half a line).
  - Exit cleanly on SIGTERM/SIGPIPE, and kill the `kotobaya watch` child.
B2. `kby do ACTION [--rev REV]`: one-shot action
  - ACTION is one of: reveal again hard g[ood easy undo skip refresh ne]xt.
  - With --rev: read the current rev from [state.json; if it differs, D]ROP the action (exit 0) and
    arrange for the next ser[ve line to carry the] notice "Ignored: screen changed". Thi[s prevents gra]ding the next card.
  - Otherwise send it to the daemon the s[ame way the -1..-4] -r / -u / -s / -R / -n flags do. Share
    one function between them; don't copy the FIFO write.
  - Unknown ACTION or an action not enabled in the current state: message on stderr, exit 2, nothing on stdout.
  - Must return quickly (aim for under 50 ms, so no daemon round-trip wait). The result shows up as
    the next `serve` line, no[t on stdout].
B3. Keep these working: `kby -f`, the wid[get confs and `kby click`, `kby ]-1..-4 -r -u -s -R -n`
    (route them through the same function as B2, without --rev).
B4. Help text: add `serve` and `do` to `k[by help, and document the line sha]pe above as a short reference (cli/README.md too).

### Done when
  - `kby serve | head -1` prints valid JSON [whether the dae]mon is up or down.
  - Every line passes `jq -e '.v == 1'`. On every "front" line, `.card.back`, `.card.sentence`
    (and answer-side `.c[ard.reading`) are absent].
  - On a "front" card, `kby do good` is refused (exit 2) and kotobaya also refuses a raw grade (A1).
  - `kby do good --rev stale-value` on a "back" card changes nothing and the next line has the
    notice. `kby do good --rev "$(jq -r .rev "$XDG_RUNTIME_DIR/kotobaya/state.json")"` grades.
  - Stopping the daemon mid-stream gives [an offline line and the str]eam keeps running. Starting it
    again resumes normal lines.
  - `kby -f` output is byte-identical to t[oday's for the same daemon state and widget] conf.
  - One assumption to check: B2 reads the current rev from state.json, so it needs serve (or -f) to be
    running. If a do with --rev can run while nothing is st[reaming, it sho]uld get the rev from the daemon instead.

## Acceptance (each bullet → at least one named test)
- [ ] A1: daemon session tests: grade on a hidden card refused, state unchanged, notice/error replied; grade after reveal works.
- [ ] A2: published state while hidden has no back/sentence (nor answer-side reading); present after reveal.
- [ ] B: a test harness for kby (bats or a pytest that runs the script against a fake `kotobaya watch`/FIFO) covering: startup loading line, every line `.v==1`, front lines omit answer fields, rev changes each line, stale --rev dropped + notice on next line, disabled action exit 2, daemon down → offline line and recovery, `-f` output byte-identical to the imported original for recorded fixture states.
- [ ] cli checks green.

## Out of scope
Server/contract changes, web, Android. The user's live install (`~/.local/bin/kby`, widget confs, systemd unit).
