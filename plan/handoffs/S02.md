# HANDOFF S02

STATUS: DONE
BRANCH: t/S02-jpdb-map
CHECK: `make check MODULE=none` n/a; spike tests `python3 -m pytest` in spikes/jpdb-map -> green (3 passed)

## Changed (≤ 6 lines)
- spikes/jpdb-map/: jpdbmap.py (mapper, page parser, API client, cache), structure.py, deeper.py, test_jpdbmap.py, REPORT.md

## Acceptance → tests
- REPORT.md with hit rate, structure, fixture diff -> done; live API command listed for human
- Hit rate 99.93% on real export vs full JMdict (GATE-S02 not triggered); jpdb vid == JMdict ent_seq
- Public deck page fetched live, 50/50 mapped -> test_parse_page, test_mapping_on_fixture_subset, test_api_uses_cache_and_key

## New dependencies
- none (stdlib only)

## For the reviewer (≤ 3 lines)
- 260 vids repeat across rows with different spellings (T207 must merge by seq). Real export has no never-forget/blacklist section, and adds known/unknown/pass/fail grades; fixture needs fixing (see REPORT).
- API endpoint/field names are unverified (no key); only the page path ran live. Private data: aggregates only, no samples printed.
