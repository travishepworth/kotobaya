# BUG-7c — Batch headword/reading/gloss in /cards and /notes tables

- Phase: P7 · Model: sonnet · Risk: low
- Deps: C-18, BUG-7b
- Branch: t/BUG-7c-batch-headwords
- Owned paths (only edit these): server dictionary module (handler for the C-18 operation, its wiring, tests), web/src/lib/cards/, web/src/routes/cards/, web/src/lib/notes/, web/src/routes/notes/
- Module(s) for CI: server, web

## Read
- contracts/api/openapi.yaml: the C-18 batch headword operation (read-only)
- plan/handoffs/BUG-7.md; web/src/lib/cards/{model.ts,api.ts,CardTable.svelte}, web/src/routes/cards/+page.svelte (rowLabel + lazy headword map from BUG-7)
- Server: existing getDictEntry handler (reuse its queries/headword logic)

## C-18 result (final contract)
`listDictHeadwords` = `GET /dict/headwords?seq=1358280,1358300` (scope read; comma-separated, explode:false, 1..500 EntrySeq; 200/401/422). Response `DictHeadwordList {items: DictHeadword[]}`, `DictHeadword {seq, headword, headword_reading, glosses: string[]}`, ascending by seq, unknown omitted, duplicates collapse, retired entries returned. Missing/empty/repeated seq, bad element, or >500 -> 422. It is currently in the WiringAuditTest 501 allowlist: remove it there (allowlist must end EMPTY). Meaning column = glosses[0] (may be empty).
Server MUST raise Netty maxInitialLineLength to >= 8192 (500 seqs with %2C-encoded commas from the TS client is ~5 kB > 4096 default); cover with a test sending 500 seqs.
Web notes map: `HeadwordMap` in web/src/lib/notes/headwords.svelte.ts, instance in web/src/routes/notes/+page.svelte (see plan/handoffs/BUG-7b.md); add a putInfo for the slim shape. Cards: the headword map in routes/cards/+page.svelte used by rowLabel.

## Do
- Server: implement the C-18 operation against the dictionary tables (one query for all seqs, unknown omitted, >500 → 422) and wire it, replacing any 501 stub. One test: mixed known/unknown seqs return the right headwords; 501 allowlist stays empty.
- Web (both /cards and /notes): after each page loads, collect the entrySeqs of referenced notes on that page and fetch their headwords in one call (chunk by 500) via the generated client; merge into the headword map used by rowLabel (cards) and the notes table Reading/Meaning columns added in BUG-7b. Cache for the page's life; on failure keep the id fallback silently. No per-row requests.

## Acceptance
- [ ] Referenced vocab rows on /cards show the headword, and on /notes show word/reading/meaning, on page load without opening anything.
- [ ] Server test for the batch operation.
- [ ] `make check MODULE=server` and `make check MODULE=web` green.

## Out of scope
/cards response shape, Android, bulk bar.
