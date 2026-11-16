# HANDOFF ONE-CARD-WEB
Status: DONE. `make check MODULE=web` green.

- Added setting `cards.reading_enabled` (bool, default false when absent) to `STUDY_SETTINGS` in `web/src/lib/settings/keys.ts`, label "Also create reading cards" (`settings.readingCards`, i18n). It shows in a new "Cards" group (`sx.cards`) in `StudySection.svelte`. It saves as a bool through the existing generic settings path.
- Add-card picker: web has no card-kind picker for creating cards. All createNote call sites send a note and the server derives its cards, so nothing needed hiding and no 422 /kind path exists. The note-detail kind tabs list only live cards of the note and were left as they are.
- No new dependencies. No contract change needed.
