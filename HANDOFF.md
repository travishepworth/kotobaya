# HANDOFF T504b

STATUS: DONE
BRANCH: t/T504b-struggle-android
CHECK: `make check MODULE=android` → green (compiles + unit tests; Android SDK present)

## Changed (≤ 6 lines)
- android/.../struggle/: Struggle screen (hot words + kanji, hot word page with sentences, make sentence card, mnemonic, partners; leeches; confusables confirm/reject; flip drill logged mode=drill), repository (last-good cache), deep link.
- android/.../widget/hotword/: 4x3 rotating hot-word Glance widget (hourly), receiver + widget_hotword_info.xml + manifest receiver; taps open `kotobaya://struggle/word/<seq>`.
- Nav hooks: struggle feature entry (bottom bar), MainActivity intent hook, AppNav deep-link LaunchedEffect, manifest host. Strings in strings_struggle.xml.

## Acceptance → tests
- make check android green; screens reachable (bottom bar "Struggle"); widget registered → manifest. No tests (UI).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Uses the app's JSON ApiClient (not generated client) like media-browse; writes (PATCH note, PUT confusable, POST note, POST review/answer) go via server, online only.
- Extra drill = all hot words (F-21). Drill UI is simple Again/Good, not the full review WebView.
- Widget hot word uses the local lapse-based stand-in (SqliteWidgetSource), not server hot score.
