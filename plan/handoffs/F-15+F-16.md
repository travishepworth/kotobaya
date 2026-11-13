# HANDOFF F-15+F-16

STATUS: DONE
BRANCH: t/F-15+F-16-wiring-audit-version
CHECK: `make check MODULE=server` → green

## Changed
- Main.kt: registered MediaApiModule (fixes GET /import/jpdb/vocab 501). BlobsModule/Imports/etc. already wired.
- App.kt: SERVER_VERSION read from generated `kotobaya-version.properties`, fallback "dev".
- server/build.gradle.kts: generateVersionResource (version from -PkotobayaVersion or env KOTOBAYA_VERSION, else "dev"); test sysprop for expected version.
- Tests: WiringAuditTest (new), ServerTest health version assertion.

## Acceptance → tests
- No implemented op answers 501 → WiringAuditTest (exact allowlist of 34 ops, W-5)
- Health version = build version → ServerTest

## New dependencies
none

## For the reviewer
- Deliberately unwired (W-5): coverage, sentences, parse, stats, grammar, confusables, leeches, hot words/kanji, TTS, export; the audit test requires the 501 set to equal the allowlist exactly, so landing a module forces removing entries.
- The image build must pass KOTOBAYA_VERSION (or -PkotobayaVersion) for a real version; otherwise health says "dev". I did not touch Dockerfile/CI (not owned).
