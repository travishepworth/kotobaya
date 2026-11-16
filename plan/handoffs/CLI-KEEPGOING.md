# HANDOFF CLI-KEEPGOING

STATUS: DONE
BRANCH: t/CLI-KEEPGOING
CHECK: `make check MODULE=cli` → green (110 tests; `bash -n cli/bin/kby` ok)

## Changed
- Daemon: state `can_keep_going` (no card, more_available, not already on); new `stop_keep_going` action (FIFO/socket); keep_going works from waiting. Schema additive.
- kby: `-k`/`keep` and `-K`/`unkeep`; serve `can_keep_going` + `actions.keep_going` (done or waiting) + `actions.stop_keep_going`; `-f` tooltip hint; default widget left-click = keep_going in done/waiting (gated on can_keep_going). README section.
- kby.orig fixture got the same default-widget button lines (byte-identity test).

Commands: `kby -k` (or `kby keep`, `kby do keep_going`) to keep going; `kby -K` (or `kby unkeep`) to stop.

## Acceptance → tests
- waiting + more_available → keep going, no early learning card → test_keep_going_from_waiting_serves_new_cards_but_not_the_learning_card_early
- can_keep_going for done/waiting/reviewing → test_can_keep_going_for_done_waiting_and_reviewing, test_keep_going_is_enabled_when_done_or_waiting_with_more_available

## New dependencies
- none

## For the reviewer
- Daemon does not gate the keep_going command (existing tests send it ungated); gating is in kby only.
- `kby serve` actions gained a `stop_keep_going` key (additive).
