# LF-A6 — Shell: dictionary and speech never wait on the network

- Phase: P10 local-first · Model: sonnet · Risk: med
- Deps: C-31, LF-A2
- Branch: t/LF-A6-dict-speech-local
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/mining/, android/src/main/kotlin/kotobaya/android/dict/ (gap fill queueing, auto-install policy), android/src/main/kotlin/kotobaya/android/media/ (resolver trigger), tests beside them
- Module(s) for CI: android

## Read
- Spec: Sync and offline (large content, offline guarantees)
- Contracts: SHELL.md L-7 (as amended), 10.7, 4.3 `createNote`, `resolveSpeech`; contracts/db/DICT_PACKAGE.md; SCHEMA.md 6.6
- Decisions: ADR-047, 050
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
1. Dictionary reads: package → sidecar → local miss answer (`404` / empty page per C-31) and enqueue the entry/query for gap fill; `shell.dict` event when it lands. No `Fallthrough`.
2. `createNote` with an entry the device lacks: per C-31 (create locally from the request's data and fill the entry later, or the documented local `503`); never a 12 s fetch on the request.
3. After the first complete sync, on an unmetered network: install scope `all` in the background (resumable; `top60k` first when nothing is installed). Respect the storage budget setting.
4. `resolveSpeech` local: `ready` + hash when `tts_audio` has cached bytes, else `pending` with `retry_after_ms` and a background resolver/prefetch request.

## Acceptance (each bullet → at least one named test)
- [ ] Test (failing-Upstream): search, entry, kanji, examples, headwords, release and `resolveSpeech` answer for hit and miss.
- [ ] Test: a miss is filled in the background and a `shell.dict` event follows; the second read hits.
- [ ] Test: every `deck_item.entry_seq` is resolvable after gap fill, so the queue counts it (design RC-5c).
- [ ] Test: auto-install is skipped on a metered network and when the budget would be exceeded.
- [ ] `make check MODULE=android` green.

## Out of scope
Search queries (LF-A5); audio byte download path (unchanged).

## Notes from previous attempts
