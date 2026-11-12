# FOLLOWUPS
<!-- Minor review findings parked here. The orchestrator turns clusters of them into tickets at each phase gate. -->
- T207 (jpdb import): jpdb vid == JMdict ent_seq; 260 vids repeat with different spellings — merge by seq. API endpoint/field names unverified (no key) — see spikes/jpdb-map/REPORT.md.
- Compose SeaweedFS has no S3 auth config (dev only); app must create S3_BUCKET on startup.
- Env var contract for api/worker (`DATABASE_URL` JDBC form, `S3_*`) was guessed by T009; P1 server/worker tickets must read exactly these names or update chart + compose together.
- Helm migrate hook is a `true` placeholder; P1 migrations ticket replaces it.
- T207: importer must still tolerate `from_anki: true` records (none in the real export, so the fixture omits them).
- Release APK is unsigned (no signingConfig); add signing via env vars in a later android ticket.
- Makefile `check MODULE=contracts` is a stub: wire to contracts/db/check.sh (+ later contract checks). Fold into T110 or a small ticket after T004–T006.
- V1 migration aborts on LC_CTYPE=C databases (pg_trgm ignores Japanese). Helm postgres (T111) and any external Postgres must init with a UTF-8 ctype (C.UTF-8); add to HUMAN_CHECKS if using an existing cluster DB.
- T003 (doc, contracts/db): concurrent re-match is arrival-order dependent (A merges W→X; B's newer entry_seq wins the register but W stays merged). Replicas converge; document as trade-off.
- T003 (doc): delete racing a duplicate — arrival order decides whether L stays live. Converges; document.
- T003 → T205/readers: deck_item `n:<loser id>` must follow merged_into_id (SCHEMA.md should say so, as for confusables).
- T003 → worker parse ticket: detected grammar span PK excludes rule_index; dedupe and keep lowest rule_index.
- T003 (doc): a duplicate's filled fields are copied to the survivor even if the duplicate was deleted before push. Mention in SUMMARY.md.
- T003 → core/android apply: append-only for review_log/change is enforced by Postgres triggers only; device apply code needs a test.
- T003 architect followups (two-level merge convergence, loser deleted_at, rejected-op rollback, etc.) moved into plan/tickets/T005.md § Must also resolve.
