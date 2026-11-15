# ARCH-CARD-TEXT — Plain-text card render on QueueItem

- Phase: P10 CLI daemon · Model: fable→opus (architect role) · Risk: medium (contract: reviewer + architect pass)
- Deps: none
- Branch: t/ARCH-CARD-TEXT
- Owned paths (only edit these): contracts/api, core, server, clients/ (regenerate via make check MODULE=contracts)
- Module(s) for CI: contracts, core, server

## Read
- docs/CLI-DAEMON.md IN FULL. It is the source of truth (human-approved 2026-11-15). Follow it exactly; where it is silent, pick the most reversible option and note it in HANDOFF.
- plan/handoffs/T604*.md (existing cli/), contracts/api/openapi.yaml for the endpoints used (read-only unless you are the architect on ARCH-CARD-TEXT).

## Do
- Additive contract change: QueueItem gains `text: {front, back, reading?, sentence?}` (plain text, no HTML), rendered by core on the server for every card kind (vocab meaning/reading, kanji, pitch, sentence/cloze, grammar…). Define per-kind field rules in the contract.
- Core: a plain-text renderer next to the HTML renderer (same field resolution incl. dictionary fill), jvm+js, goldens per kind.
- Server: fill `text` in getReviewQueue items. Regenerate clients.
## Acceptance
- [ ] Goldens: plain-text render for every card kind (jvm=js).
- [ ] Server test: queue items carry text for each kind; Takoboto-shaped note (fields {} + entry_seq) renders front/back from the dictionary.
- [ ] make check MODULE=contracts (no drift), core, server green.

## Notes
- No new dependencies expected (stdlib tomllib/socket/os.mkfifo + the existing generated client). Any addition goes in HANDOFF.md with a reason.
- Never hand-edit clients/; regenerate.
