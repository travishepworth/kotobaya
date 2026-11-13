# HOTFIX-0.1.7 — Preview effect loop hangs browser (I-3) + API heap default (I-1 stopgap)

- Phase: P7 · Model: haiku (writer-light) · Risk: low
- Deps: —
- Branch: t/HOTFIX-0.1.7-preview-loop-heap
- Owned paths (only edit these): web/src/lib/cards/, web/src/lib/notes/, web tests for those, deploy/helm/kotobaya/ (values.yaml + api deployment template), plan/HUMAN_CHECKS.md is NOT yours (orchestrator adds the deploy entry)
- Module(s) for CI: web, deploy

## Read
- Evidence: plan/handoffs/INBOX-0.1.6.md › I-3 and I-1 (only those two sections)
- Spec/contracts: none needed

## Do
1. web/src/lib/cards/CardPreview.svelte (~line 28-29) and web/src/lib/notes/NoteEditor.svelte (~line 63-64, `dictLoading`):
   the Svelte 5 `$effect` writes `loading` and then reads it back, so `loading` becomes a dependency; the fetch's
   finally sets it false, the effect re-runs, refetches from cache in a microtask, loops forever and hangs the tab.
   Fix: compute a local `const busy = seq !== null || lit !== null; loading = busy; if (!busy) return;` (or `untrack`).
   Never read inside an effect the state that effect writes. Scan both files (and other components in those two dirs)
   for the same pattern.
2. One vitest per component that mounts the preview with a dictionary-backed note (entrySeq set, and a kanji note
   with kanjiLiteral), mocks the fetch, and asserts the fetch/effect runs a bounded number of times (e.g. ≤ 2) and the
   loading state settles. NoteEditor test also asserts `onentry` is called a bounded number of times.
3. Helm chart: the api container gets env `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70` by default, overridable
   through `api.env` (a user value for JAVA_TOOL_OPTIONS in api.env wins; no duplicate env names in the rendered pod).
   Extend deploy/helm/check.sh (or its assertions) to check the default renders and the override wins.

## Acceptance
- [ ] CardPreview and NoteEditor effects bounded → named vitest tests
- [ ] Chart default JAVA_TOOL_OPTIONS present, override wins, no duplicate env → check.sh assertion
- [ ] make check MODULE=web and MODULE=deploy green

## Out of scope
Any visual change; stats query rewrite (BUG-9); server Dockerfile changes.
