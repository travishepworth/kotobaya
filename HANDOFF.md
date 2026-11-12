# HANDOFF S01

STATUS: DONE
BRANCH: t/S01-ankidroid-standin
CHECK: no module (standalone). `cd spikes/ankidroid-standin && ./gradlew assembleStandinDebug assembleAnkidroidDebug` → green; both APKs built, aapt confirms package names, authority, permission.

## Changed (≤ 6 lines)
- `spikes/ankidroid-standin/`: standalone Gradle Android app (AGP 8.7.3, Kotlin 2.1.10, own wrapper copy).
- `StandinProvider`: FlashCardsContract decks/models/templates/fields/notes(+v2)/cards, duplicate query (mid/csum/sfld), in-memory store; logs every call + returned rows.
- `LogActivity`: on-screen log, Clear/Share; also logs CREATE_FLASHCARD / SEND intents. Logcat tag `AnkiStandin`.
- Flavors: `standin` (default, `kotobaya.spike.ankistandin`) and `ankidroid` (`com.ichi2.anki`).
- README: build command and step-by-step manual Takoboto test.

## Acceptance → tests
- Both APK variants build → `assembleStandinDebug`, `assembleAnkidroidDebug` (verified).
- README manual test → `spikes/ankidroid-standin/README.md` § Manual test.

## New dependencies
- none (plain framework Activity, no AndroidX).

## For the reviewer (≤ 3 lines)
- Contract restated from memory of AnkiDroid's FlashCardsContract/AddContentApi (incl. `com.ichi2.anki.provider.spec`=2 meta-data); unknown URIs are logged, not thrown, so gaps show in the log.
- HUMAN_CHECK: run the manual test on a phone with Takoboto; on Android 11+ package visibility may force the `ankidroid` variant.
