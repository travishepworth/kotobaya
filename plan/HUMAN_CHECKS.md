# HUMAN CHECKS

Everything the build needs from you after the code is written. The orchestrator adds specifics
(APK paths, commands, questions from blocked tickets) as it goes. Work top to bottom: later
checks assume earlier ones pass. Report problems back to the orchestrator as new tickets.

## Fill in when you can (placeholders used until then)
- [ ] `docs/DECISIONS.md` › Cluster: arch, registry URL, domain, DNS provider, existing Postgres/S3, restic target.
- [ ] `.env.local` with `JPDB_API_KEY`, and `fixtures/private/jpdb-export.json` (both git-ignored).
      The orchestrator adds the exact commands for the live jpdb runs (S02, T207) here.

## Contracts (deferred GATE-CONTRACTS)
- [ ] Read `contracts/SUMMARY.md`, especially "3 choices most worth a second look". Changes you want
      become `C-*` tickets (breaking ones with a migration).

## Provisional decisions
<!-- orchestrator appends: ADR id, one line, how to reverse. Confirm or reverse each. -->

## Deploy
- [ ] **P1 — Cluster:** `helm install` on the real cluster (command filled in by orchestrator); open the web app; search the dictionary.
- [ ] **Backups:** confirm the first nightly backup landed off-cluster; run the restore drill once.

## Takoboto (spike + production)
- [ ] **S01/T305:** install the default APK (`com.ichi2.anki` flavor). In Takoboto, add 5 words. Confirm they appear as notes with the right fields.
- [ ] If the own-app-ID flavor is preferred, try it too and record which works in `docs/DECISIONS.md`.

## jpdb import
- [ ] **S02:** read `spikes/jpdb-map/REPORT.md`. Hit rate ≥ 95%? If not, work through the mapping review queue in the web app.
- [ ] **P2 — History import:** import your jpdb review history; spot-check 20 cards' due dates against jpdb.

## Daily use
- [ ] **P2:** review in the web app for 3 days. Note anything off.
- [ ] **P3:** pair the phone; review offline (airplane mode) for a week; sync; confirm review counts match on web and phone.
- [ ] **P3:** widget sizes, due-count notification, Quick Settings tile, share-to-app, Yomitan via on-device AnkiConnect.
- [ ] **P4:** import two shows you have on jpdb; compare coverage numbers (expect within ~3 points).
- [ ] **P4:** import one of your own files (subtitles or EPUB); fix a mis-parsed word in the reader.
- [ ] **P5:** hot words and leeches look sensible after a week of data; grammar cards appear from your own sentences.
- [ ] **P6:** TTS audio, pitch display, CLI (`kotobaya cards find 'is:due'`), export.

## Blocked tickets and open questions
<!-- orchestrator appends: ticket, question, options, what's skipped because of it -->
