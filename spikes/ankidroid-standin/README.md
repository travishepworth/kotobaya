# S01 spike: AnkiDroid stand-in

A throwaway Android app that pretends to be AnkiDroid's add-card API so we can see what Takoboto sends.
It registers AnkiDroid's provider authority `com.ichi2.anki.flashcards`, declares the permission
`com.ichi2.anki.permission.READ_WRITE_DATABASE`, and answers the `FlashCardsContract` URIs used by
AnkiDroid's `AddContentApi` from an in-memory store (lost when the process dies):

| URI | Supported |
|---|---|
| `decks`, `decks/#`, `selected_deck` | query; insert into `decks` (add deck); update `selected_deck` |
| `models`, `models/#`, `models/current` | query; insert into `models` (add note type) |
| `models/#/templates[/#]`, `models/#/fields` | query; insert/update templates |
| `notes`, `notes_v2` | query (duplicate check: `mid=`, `csum in (...)`, `sfld=`); insert, bulkInsert (`?deckId=`) |
| `notes/#`, `notes/#/cards[/#]` | query; update (fields/tags, card deck move); delete note |
| anything else, `call()` | logged, empty answer |

Every call (caller package, URI, projection, selection, args, values, and the rows returned) is
logged to logcat under tag `AnkiStandin` and shown on the app's screen. Field separators show as
`<US>`. Intents of `org.openintents.action.CREATE_FLASHCARD` and `SEND text/plain` are logged too.
It seeds a deck "Default" (id 1) and a note type "Basic" (Front/Back).

## Variants

| Variant | applicationId | Use |
|---|---|---|
| `standin` (default) | `kotobaya.spike.ankistandin` | Try first. |
| `ankidroid` | `com.ichi2.anki` | Fallback if Takoboto checks for AnkiDroid's package name, or (Android 11+) only declares `<queries><package android:name="com.ichi2.anki"/>`. Sideload only. |

Only one app on a device can own the authority and the permission: uninstall real AnkiDroid and the
other variant first, or install fails with `INSTALL_FAILED_CONFLICTING_PROVIDER` / `DUPLICATE_PERMISSION`.

## Build

Needs JDK 17 and the Android SDK (platform 35) via `ANDROID_HOME` or a `local.properties` with `sdk.dir=...`.

```sh
cd spikes/ankidroid-standin
./gradlew assembleStandinDebug assembleAnkidroidDebug
# -> build/outputs/apk/standin/debug/ankidroid-standin-standin-debug.apk
# -> build/outputs/apk/ankidroid/debug/ankidroid-standin-ankidroid-debug.apk
```

This is a standalone Gradle build; it is not part of the root build or `make check`.

## Manual test

1. Connect the phone (`adb devices`). Make sure AnkiDroid is not installed: `adb uninstall com.ichi2.anki`.
2. Install the default variant:
   `adb install -r build/outputs/apk/standin/debug/ankidroid-standin-standin-debug.apk`
3. Open "AnkiDroid stand-in (spike)" once so the process starts, then clear old logs: `adb logcat -c`.
4. Start capturing: `adb logcat -v time -s AnkiStandin:I > takoboto-standin.log`
5. In Takoboto: Settings → AnkiDroid (enable export; grant the "Read and write to the AnkiDroid
   database" permission when asked). Pick the deck and note type it offers (or let it create its own).
6. Look up a word (e.g. 食べる) and tap the add-to-AnkiDroid button. Add the same word a second
   time to trigger the duplicate check. Add one word with an example sentence.
7. Stop logcat (Ctrl-C). Optionally also tap "Share log" in the stand-in to send the on-screen log.
8. If Takoboto says AnkiDroid is not installed, or nothing appears in the log: uninstall the
   stand-in (`adb uninstall kotobaya.spike.ankistandin`), install
   `build/outputs/apk/ankidroid/debug/ankidroid-standin-ankidroid-debug.apk`, and repeat from step 3.
9. Record in the T305 ticket: which variant worked, the note type name and field list Takoboto
   creates or picks, the exact `flds` it inserts (field order, HTML, furigana format), its tags,
   and the duplicate-check URI/selection. Attach `takoboto-standin.log`.

Log lines to look for: `insert ... uri=content://com.ichi2.anki.flashcards/models` (note type
creation), `insert ... /notes` (the word), `query ... /notes_v2 ... csum in (...)` (duplicate check).
