# SHELL-L5b — Local mining follow-ups (from SHELL-L5 review)

- Model: sonnet (writer) · Risk: med · Deps: SHELL-L5 · Gradle: yes
- Owned: android/src/main/kotlin/kotobaya/android/shell/api/LocalApi.kt, android/src/main/kotlin/kotobaya/android/shell/api/mining/**, android/src/main/kotlin/kotobaya/android/dict/DictGapCache.kt (index only), android/src/test/**/shell/**
## Do
1. LocalApi stamps `kotobaya-local: 1` on forwarded answers too (SHELL.md 3.3 forbids). Add a `forwarded` flag on ShellResponse respected by LocalApi; set it on every forwarded/merged-from-server answer.
2. CreateNoteRoute: `source_sentence_id`/`source_media_id` reject JSON null like the server (`e.uuid`), 422.
3. DictSearch merge: carry the package's own grade/freq into the merge key so `l.` cursors page without skips/repeats.
4. Performance: local search / listKanjiWords decode every cached ENTRY row and run one presentEntries query per row. Index cached forms/readings (sidecar table or in-memory snapshot per cache generation) so search-as-you-type stays fast with thousands of gap-filled entries. Add a test with ~3000 cached entries asserting one bounded query path.
5. MediaSentences: replace the `instr(tokens,'"seq":N')` compact-JSON pre-filter with json_each (or rely on parsed check).
## Acceptance
- [ ] Tests for 1–5; make check MODULE=android green.
