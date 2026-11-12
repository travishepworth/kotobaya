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
