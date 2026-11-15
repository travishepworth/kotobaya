# WRK-FREQ-MATCH — Frequency-list decks: map each source row to ONE entry (no homophone fan-out), then rebuild

- Phase: P9 · Model: opus · Risk: high (changes entry_freq used by list decks, dict ranking and the device dict scope) · Review: reviewer
- Deps: none
- Branch: t/WRK-FREQ-MATCH
- Owned paths: worker/src/kotobaya_worker/dict/extra/, worker/src/kotobaya_worker/decks/ (only if the band query needs it), worker/src/kotobaya_worker/parse/match/ ONLY to expose its existing candidate ranking as a reusable function (no behaviour change for the parser; parser fixture tests must stay green), worker/src/kotobaya_worker/jobs/ (a rebuild entry point), worker CLI entry for that job, worker/tests/
- Module(s) for CI: worker

## User report (2026-11-15)
The "conversation 10k" deck (CEJC freq source) contains, for the single kana term そう, ~16 unrelated entries: 宋, 壮, 草, 装, 走, 叢, 索, 惣, 疎雨, 奏, 叟, 曹, 葬, 躁, 宗, 甑. Same for だ, か, ね... The jpdb (jpdb v2.2 kana freq) and literature (BCCWJ) decks have the same problem: all three are frequency-list decks from worker dictSourcesJson (keys jpdb, bccwj, cejc).

## Root cause (scout)
- dict/extra/load.py:136-143: each row calls idx.match(r.term, r.reading) and gives EVERY returned seq that row's rank (best = min).
- dict/extra/index.py:23-29: match() returns every entry having the term as any form; reading filter keeps any entry with that reading. For kana-only そう every homophone passes. No POS, usually-kana or priority use.
- decks/build.py:138-150: band = entry_freq.rank <= N, so every homophone enters the deck. Side effect: obscure homophones get top ranks in entry_freq, which also skews dictionary ranking and the device dict "top60k" scope.
- Rows: term, optional reading, rank (dict/extra/freq.py:13-35). Check whether the jpdb kana source encodes "written in kana" vs kanji form (yomitan freq metadata like {reading, frequency:{displayValue}} or ㋕ markers) and use it.
- The media/literature parser already picks one seq per token (parse/match match_seq: reading, POS, kana-usual, frequency, lowest seq).

## Do
1. One row -> at most one entry_seq (pick the best candidate):
   - term with kanji: candidates having that kanji form (and the reading, if given). Rank: priority tags (ichi1/news1/spec1/nfXX) > form is the entry's first kanji form > lowest seq.
   - kana-only term: candidates where the term is a reading. Rank: entry has no kanji form or the term is marked usually-kana (uk) on its first/common sense > priority tags > lowest seq. 草/宋/躁 etc. must lose to the common usually-kana entry (そう adverb "so; that way", 1006710-ish; だ copula; か/ね particles).
   - If a source row is kana and a separate row in the same source carries the kanji form, the kanji row maps its own entry; the kana row must not also claim the kanji entry unless it is that entry's usually-kana form.
   - Share ranking with parse/match where it fits (extract, don't duplicate); the parser's behaviour must not change.
2. Unmatched / ambiguous-with-no-winner rows: counted and logged; nothing silently dropped (mapping counts per source in the job output).
3. Rebuild path: a worker job/CLI that recomputes entry_freq from the cached sources (no re-download needed if cached; otherwise uses the pinned sha256 sources) and re-enqueues list-deck builds (decks/enqueue.py), bumping content_version so clients get deck.content_published. Document the exact kubectl command in HANDOFF (worker runs in k3s namespace kotobaya).
4. User data: word_status is keyed by entry_seq, not deck_item; removed deck items lose nothing. Note it in HANDOFF.

## Acceptance
- [ ] Test (fixture): kana term そう (no reading) with candidates {usually-kana common adverb, 草, 宋, 躁, 葬...} maps to exactly the adverb; だ -> copula; か -> particle.
- [ ] Test: kanji term 草 with reading そう maps to 草 only; a kanji term with two entries picks the priority-tagged one.
- [ ] Test: mapping counts per source (rows, mapped, unmatched) sum to the input; one seq per mapped row.
- [ ] Parser/coverage fixture agreement tests still green.
- [ ] make check MODULE=worker green.

## Out of scope
jpdb deck importer (importers/jpdb_deck, already one seq per row), media pipeline, clients, server.
