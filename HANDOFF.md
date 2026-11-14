# HANDOFF C-21

STATUS: DONE
BRANCH: t/C-21-jlpt-vocab
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- `contracts/db/V3__vocab_list.sql`: server-only `ref.vocab_list`, `vocab_list_item`, `vocab_list_unmatched`. `test/smoke_v3.sql` covers them.
- `contracts/db/VOCAB_LISTS.md`: the source, its licence, the matching rule, the unmatched accounting, the catalogue, `deck-build` semantics and the new internal `deck-publish` kind.
- `media/deck-publish.v1.schema.json`, an example, and a `validate.py deck-publish` check with 7 negative cases.
- openapi: `/deck-lists` rules; `JobResultDeckBuild` gains `list_key`/`changed`/`unmatched`; `JobResultDictRefresh` gains `vocab_lists`. Clients regenerated.
- SCHEMA/SNAPSHOT/DICT_PACKAGE pointers; ADR-022 (provisional).

## Acceptance → tests
- Additive, check green → `contracts/db/check.sh` (smoke_v3, validate.py)
- ADR names source, licence, matching, unmatched reporting → ADR-022 and VOCAB_LISTS.md 2–3

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Note for JLPT-1: the JLPT source is on by default (pinned stephenmk CSVs, no Helm change). The worker loads it inside dict-refresh, then rebuilds every live list deck. Also needed: the deck-build handler, a server `DeckPublishRunner`, and `DeckLists` counts per 4.1.
- Choices worth checking: N4–N1 exclude words already in an easier level; JLPT decks are ordered by global frequency.
- Source licence is CC BY-SA 4.0 (upstream data CC BY).
