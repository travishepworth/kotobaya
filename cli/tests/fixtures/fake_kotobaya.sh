#!/usr/bin/env bash
# Stand-in for `kotobaya watch --format json`: relays the lines in $FAKE_STATES.
# FAKE_ONESHOT=1: print them and exit; otherwise follow the file (the test appends lines).
# $FAKE_DIR/die-once present: remove it, print to stderr and exit 3 (a dying watch).
[[ ${1:-} == watch ]] || exit 64
if [[ -e ${FAKE_DIR:-/nonexistent}/die-once ]]; then
  rm -f -- "$FAKE_DIR/die-once"
  echo "boom" >&2
  exit 3
fi
if [[ ${FAKE_ONESHOT:-} == 1 ]]; then exec cat -- "$FAKE_STATES"; fi
exec tail -n +1 -f -- "$FAKE_STATES"
