# WEB-TAKO-SEARCH — Web: Takoboto notes show wrong text, their previews fail; merge cards+notes search

- Phase: P9 hotfix · Model: sonnet · Risk: med
- Deps: none (main @ cb54637e)
- Branch: t/WEB-TAKO-SEARCH-web
- Owned paths (only edit these): web/src/ (routes/cards, routes/notes, a new merged search route or nav change, lib/cards, lib/state, lib/ui as needed), web tests. If the root cause is in core rendering or server (wrong stored field keys, CardGenerator mapping), STOP that part and report it in HANDOFF with evidence. Don't edit core/ or server/.
- Module(s) for CI: web

## Symptoms (user, live server 0.1.11 + phone 0.1.13/0.1.14)
1. In the web Cards/Notes browser, notes that came from Takoboto (added on Android through the AnkiDroid provider) show incorrect text. The exact text isn't known yet. Scout: rowLabel (web/src/lib/cards/model.ts:48-61) falls back to the headword via entrySeq, then noteId.slice(0,8). Takoboto notes may have fields={} or fields under unexpected keys, so the row may show an 8-char id or the wrong field.
2. Preview of those notes fails (web/src/lib/state/preview.svelte.ts:27-53 returns ok:false).
3. User wants the Android-style merged search on the web too: ADR-026 now applies to web. One search box for cards and notes (plus dictionary hits flagged "In my cards"), replacing separate hunting across /cards, /notes, /dictionary. Keep the core search parser for card-query syntax (operators → card results first).

## Do
1. Reproduce the exact shape: find what op/note Android mints for a Takoboto send (android/src/**/ankiapi/store/TakobotoAnkiStore.kt, notes/NoteCreator.kt; client model "jp.takoboto" fields Japanese/Reading/Meaning/Note/Sentence/SentenceMeaning/Link mapped to [expression, reading, meaning, null, sentence, sentence_translation, null]; note type = the Takoboto note type, ADR-013). Build a web fixture/test with that exact note + note type + card and run rowLabel and the preview path (core renderCard via web/src/lib/core) on it. Find why the text is wrong and the preview fails.
2. Fix in web (label = expression field → dictionary headword → kanji; never a raw id while loading. Show a skeleton instead. Preview renders with dictionary fill).
3. Merged search page per ADR-026 (web): one route (e.g. /search, linked from nav; keep /cards and /notes for bulk table work or fold them in, your call, documented in HANDOFF), with result rows showing word, reading, meaning, and in-my-cards status/deck. Data only via the generated client; domain logic from web/src/lib/core.
4. i18n for all new strings.

## Acceptance
- [ ] Test: Takoboto-shaped note → rowLabel shows the word (not an id, not a field name).
- [ ] Test: preview of a Takoboto-shaped card renders ok (non-empty, contains the word).
- [ ] Test: merged search state returns dictionary + my-card results in ADR-026 order with the flag.
- [ ] make check MODULE=web green.

## Out of scope
Android, server changes (report them), deploy.
