# HANDOFF T101

STATUS: DONE
BRANCH: t/T101-dict-pipeline
CHECK: `make check MODULE=worker` → green (13 tests; DB tests need docker, skip otherwise)

## Changed (≤ 6 lines)
- `worker/src/kotobaya_worker/dict/`: fetch (sha256 + cache, injected fetcher), jmdict/kanjidic/krad parsers (streaming), normalize, dburl (JDBC → libpq), load (one tx, COPY staging, upsert/retire/reappear/is_current), pipeline.refresh() for T109.
- `worker/tests/dict/`: unit tests + Postgres 17 (docker, `--lc-ctype=C.UTF-8`, V1 applied, DB cloned per test).
- pyproject.toml, uv.lock: psycopg.

## Acceptance → tests
- 2000 entries / form / gloss / entry_kanji / 612 kanji, counts computed from raw XML → test_fixture_counts_match_xml
- Rerun same id unchanged (counts + md5); retire/reappear → test_rerun_same_release_changes_nothing, test_retire_and_reappear
- Checksum mismatch writes nothing; cache not re-downloaded → test_refresh_checksum_mismatch_writes_nothing, test_cached_file_is_not_redownloaded, test_refresh_end_to_end_with_cache
- worker check green.

## New dependencies
- psycopg[binary]>=3.2 (3.3.9) — Postgres driver, COPY support; none existed.

## For the reviewer (≤ 3 lines)
- JMdict tags: entities (`&v1;`) are kept as codes by pre-substituting markers; DTD is skipped. pos inherits from previous sense; `is_common` = news1/ichi1/spec1/spec2/gai1. `ref.kanji.radical` = KANJIDIC2 classical radical number as text (contract doesn't say). Only English KANJIDIC meanings.
- Source checksums are caller-supplied (`Sources.edrdg(...)`); release id/version supplied by caller; older id than loaded → ValueError. KRAD tested only on synthetic zip (no KRAD fixture); URLs unverified offline. Retired entries keep entry_kanji.
