# FOLLOWUPS
<!-- Minor review findings parked here. The orchestrator turns clusters of them into tickets at each phase gate. -->
- T207 (jpdb import): jpdb vid == JMdict ent_seq; 260 vids repeat with different spellings — merge by seq. API endpoint/field names unverified (no key) — see spikes/jpdb-map/REPORT.md.
- .gitignore `build/` swallows `deploy/build/` — add `!deploy/build/` (T001-owned file; fold into next root-files ticket).
- Compose SeaweedFS has no S3 auth config (dev only); app must create S3_BUCKET on startup.
