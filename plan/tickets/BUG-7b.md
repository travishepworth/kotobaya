# BUG-7b — Card browser: batch headword labels for referenced notes

- Phase: P7 · Model: sonnet · Risk: low
- Deps: C-18
- Branch: t/BUG-7b-headwords
- Owned paths (only edit these): server dictionary module (handler for the C-18 operation, its wiring, tests), web/src/lib/cards/, web/src/routes/cards/
- Module(s) for CI: server, web

## Read
- contracts/api/openapi.yaml: the C-18 batch headword operation (read-only)
- plan/handoffs/BUG-7.md; web/src/lib/cards/{model.ts,api.ts,CardTable.svelte}, web/src/routes/cards/+page.svelte (rowLabel + lazy headword map from BUG-7)
- Server: existing getDictEntry handler (reuse its queries/headword logic)

## Do
- Server: implement the C-18 operation against the dictionary tables (one query for all seqs, unknown omitted, >500 → 422) and wire it, replacing any 501 stub. One test: mixed known/unknown seqs return the right headwords; 501 allowlist stays empty.
- Web: after each page of /cards loads, collect the entrySeqs of referenced notes on that page and fetch their headwords in one call (chunk by 500) via the generated client; merge into the existing headword map that rowLabel uses. Cache for the page's life; on failure keep the id fallback silently. No per-row requests.

## Acceptance
- [ ] Referenced vocab rows show the headword on page load, without opening the preview.
- [ ] Server test for the batch operation.
- [ ] `make check MODULE=server` and `make check MODULE=web` green.

## Out of scope
/cards response shape, Android, bulk bar.
