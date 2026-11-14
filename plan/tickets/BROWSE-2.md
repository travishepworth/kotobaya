# BROWSE-2 — Browse: one row per vocab note (collapse reading + meaning cards)

- Phase: P10/P11 (0.1.10 if small, else 0.1.11) · Model: sonnet · Risk: low
- Deps: BROWSE-1 if both touch the browse state (serialize)
- Branch: t/BROWSE-2-note-rows
- Owned paths: web/src/** (browse/cards route + lib/state), server/** only if the browse API cannot group (prefer client grouping by note id of the returned page; if paging makes that wrong, CONTRACT_CHANGE_REQUEST for a note-grouped browse mode)
- Module(s) for CI: web

## Do
User report (web): Browse lists a vocab note's reading card and meaning card as separate rows. Collapse to one row per note/word showing the headword, with an expander (or inline chips) for per-card state (kind, due, state, lapses). Selecting/acting on the row must still allow per-card actions. i18n strings.
## Acceptance
- [ ] Grouping helper in lib/state with a vitest (two cards of one note → one row with two card states; paging boundary behaviour stated).
- [ ] make check MODULE=web green.
