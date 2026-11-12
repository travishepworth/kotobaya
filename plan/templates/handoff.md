# HANDOFF <ID>

STATUS: DONE | BLOCKED | CONTRACT_CHANGE_REQUEST
BRANCH: t/<ID>-<slug>
CHECK: `make check MODULE=<module>` → green | red (why)

## Changed (≤ 6 lines)
- <what, where>

## Acceptance → tests
- <bullet> → <test name>

## New dependencies
- <name@version — why> | none

## For the reviewer (≤ 3 lines)
- <the riskiest part, a decision you made the ticket didn't, anything you're unsure of>

## If BLOCKED
QUESTION: <one precise question, with the options you see>

## If CONTRACT_CHANGE_REQUEST
CONTRACT: <file>
CHANGE: <exact proposed change>
WHY: <what breaks without it>
ADDITIVE: yes | no
