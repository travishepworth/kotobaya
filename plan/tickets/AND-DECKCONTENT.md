# AND-DECKCONTENT — Media/list deck content never installed on the device ("Fatal Twelve" empty on phone)

- Phase: P9 hotfix · Model: opus · Risk: high
- Deps: none (main @ 89a8c690)
- Branch: t/AND-DECKCONTENT-install
- Owned paths (only edit these): android/src/**/sync/, android/src/**/media/, android/src/**/decks/ (data layer only: repository/installer; not composables), android/src/**/di|Koin modules for wiring, android/src/test/; core/ only if the snapshot decoder belongs there (say so)
- Module(s) for CI: android (+ core if touched)

## Symptom (live 0.1.12 APK, server 0.1.11, phone user 10)
Media deck "Fatal Twelve" shows as empty on the phone; the web shows its content. Phone DB copy (PRIVATE, read-only, never commit/copy rows):
/tmp/claude-1000/-home-travmonkey-repos-kotobaya/c6911de0-217a-4b52-b8c2-af6c5b745fd0/scratchpad/dbcopy/kotobaya-app.db (+ -wal)
deck row: type=media, content_version=1, installed_content_version=0, content_blob_hash set; deck_item has 0 rows for it; media/sentence/occurrence tables empty. Same for list decks (jlpt-n5 smoke decks: content_version 1, installed 0). A second, deleted "Fatal Twelve" (deleted_at set) also exists — must stay hidden.

## Read
- contracts/db/SNAPSHOT.md (deck items + media content blobs, install steps, esp. §"Device only" installed_content_version), contracts/db/MEDIA_PIPELINE.md §ops, contracts/api (getBlob)
- Existing BlobStore / AudioPrefetchWorker (WorkManager, Range-resume, SHA-256) for download patterns.

## Do
1. Confirm nothing on Android downloads/installs deck-items or media-content blobs (grep installed_content_version).
2. Implement the device installer per SNAPSHOT.md: when a live media/list deck (and its media row) has content_version > installed_content_version, fetch the blob by content_blob_hash, verify hash + header (deck_id, deck_type, content_version), install deck_item (and media_segment/sentence/occurrence for media content) in one transaction, set installed_content_version. Trigger after sync (when deck.content_published / media.content_published pulled) and at startup for pending ones; WorkManager, network-constrained, retry with backoff. Idempotent; never touches learning data or pending_op.
3. Deck list/detail data layer must report "downloading / not yet downloaded" vs truly empty (expose a state; the UI ticket AND-LABELS may render it — add one i18n string + minimal display if trivial, else note it).

## Acceptance
- [ ] Test: fixture deck-items + media-content blobs install; counts match header; installed_content_version set; re-run is a no-op.
- [ ] Test: hash/header mismatch → nothing installed, version unchanged, error logged (tag KotobayaContent).
- [ ] Test: a newer content_version replaces old deck_items atomically.
- [ ] make check MODULE=android green.

## Out of scope
Server, web, review queue rules, search UI, deck item labels (AND-LABELS), Takoboto (AND-TAKO-PERF).
