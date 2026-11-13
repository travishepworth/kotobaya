# HANDOFF T306 + T311

STATUS: DONE
BRANCH: t/T306+T311-share-ankiconnect (one commit per ticket, rebased on main)
CHECK: `make check MODULE=android` → green

## Changed
- T306 `intake/`: `IntakeParser` (pure), `IntakeModel`, `IntakeActivity` (confirm sheet; `kotobaya://word/<id>` shows a "not available yet" stub inside the activity, no nav route), `IntakeFeature`; manifest activity + 3 intent-filters.
- T311 `ankiconnect/`: hand-rolled loopback-only HTTP server, v6 protocol handler, `StoreAnkiConnectBackend` over `TakobotoAnkiStore` (notes go through NoteCreator, so ops), settings section, plain `Service` entry.
- Out-of-path edits: `res/values/strings_intake.xml`, `strings_ankiconnect.xml`, `di/FeatureModules.kt` (2 lines before `syncFeature`).
- Carried DuplicateCheck item: already done by T305c (`includeMatched` when no match); skipped.

## Acceptance → tests
- IntakeParser cases → `IntakeParserTest`; intents resolve + create note (fake services) → `IntakeActivityTest`
- Fixtures per action → `AnkiConnectProtocolTest` + `src/test/resources/ankiconnect/*.json`
- Loopback binding, origin, API key → `AnkiConnectServerTest`; duplicate error, `multi`, real store ops → `StoreAnkiConnectBackendTest`

## New dependencies
- none (no embedded engine in the Android deps; used `java.net.ServerSocket`)

## For the reviewer
- `allowDuplicate` is not honoured (words learned once). Media: `storeMediaFile` writes `<sha256>[.ext]` to the blob cache (reason media); references are rewritten, but there is no blob upload or renderer convention yet. `multi` returns full `{result,error}` envelopes (from memory of AnkiConnect).
- Default allowed origins include `moz-extension://*` and `chrome-extension://*`. Server is not auto-restarted at app start (user re-toggles) and `url`/`path` media is refused.
