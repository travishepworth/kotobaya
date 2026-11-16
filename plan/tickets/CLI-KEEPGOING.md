# CLI-KEEPGOING — Keep going from the CLI/daemon in every state + simple kby usage

- Phase: P10 · Model: sonnet (writer) · Risk: med
- Branch: t/CLI-KEEPGOING · Owned paths: cli/ · Module: cli

## Read
- docs/DECISIONS.md ADR-031 (keep going), ADR-033 (waiting state, counts independent of keep going)
- cli/README.md (daemon states, kby), plan/handoffs/RC-CLI.md (keep_going now runs as a background task)

## User request (2026-11-16)
"The cli should support keep going (and update my kby wrapper to make it simple to use)."
Today keep_going is only offered from `done` (README:162), not from `waiting`. Web and Android offer it
whenever the server says more_available (including while waiting).

## Do
1. Daemon: keep_going allowed whenever the latest queue has more_available (done, done-for-now and
   waiting states), mirroring web/Android. While keep going, a learning card that becomes due is still
   served at its due instant (no learn-ahead). State JSON exposes a clear `can_keep_going` (bool) and
   `keep_going` (active) flag (additive schema change in cli/schema/state.v1.json). Option to stop keep
   going (back to normal limits) if not already present.
2. kby: one obvious action — `kby -k` and `kby keep` (alias `kby do keep_going`) — plus show it in
   `kby help`, in the `-f` widget output buttons/tooltip when can_keep_going, and in `kby click`
   if click maps actions. Keep the keypress path pure bash.
3. README: document it in one short section.

## Acceptance
- [ ] Test: waiting state with more_available → keep_going command fetches keep-going queue and serves cards; waiting learning card still not served early.
- [ ] Test: state JSON has can_keep_going true/false correctly for done/waiting/reviewing.
- [ ] make check MODULE=cli green; `bash -n cli/bin/kby`.
