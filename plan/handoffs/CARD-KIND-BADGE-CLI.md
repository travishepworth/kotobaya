# HANDOFF CARD-KIND-BADGE-CLI

STATUS: DONE
BRANCH: t/CARD-KIND-BADGE-CLI
CHECK: `make check MODULE=cli` -> green (111 passed, mypy, ruff)

## Changed
- backend.py/session.py: CardEntry.note_type from queue item card.note.type; state card gets `kind_label` (Vocab/Kanji/Phrase/Grammar/Sentence/Custom, same as web); omitted if the note is not sent.
- schema/state.v1.json: additive optional card.kind_label.
- watch.py: template field kind_label; waybar tooltip prefixed "Kanji: ...".
- bin/kby: serve card.kind_label; flat fields kind_label/kind_badge/kind_prefix (only when present, so output stays byte-identical to the original); default widget shows "[Kanji] " before the front and "Kanji · " in tooltips. Pure bash/jq path unchanged.

## Acceptance -> tests
- CLI state field has a test -> tests/test_daemon.py::test_card_state_carries_kind_label_from_note_type

## New dependencies
- none

## For the reviewer
- CLI has no per-card "new" marker today, so the label sits in the front text and tooltip instead. Card.note is optional in the contract; missing means no label.
