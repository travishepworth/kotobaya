# ONE-CARD — One card per word: merge reading + meaning cards (design first)

- Phase: P10 · Model: opus (architect) · Risk: high (data model, sync, scheduling, migration of live data)
- Step 1 (this dispatch): DESIGN ONLY — proposal + ADR draft + ticket split. No code.

## User decision (2026-11-16, verbatim)
"reading and meaning cards are the same to me. They should 100% of the time be the same card and
reviewed once."
Context: vocab notes currently have separate `meaning` and `reading` cards (siblings). The user reviewed
meaning cards yesterday and saw their reading siblings today as New — they want one card per word,
reviewed once (jpdb-style: front = word, back = reading + meaning).

## Design questions to answer (recommend one option each, with why + how to reverse)
1. Scope: which note types get one card (vocab certainly; kanji? phrase/sentence? grammar)? What do
   existing templates produce per type today?
2. Model: stop generating a `reading` card for new notes; front/back template of the single card
   (shows reading + meaning on back; does the front need furigana off?).
3. Migration of existing data (live DB, ~tens of thousands of cards, jpdb-imported history):
   - For each sibling pair, which card survives and whose FSRS state (stability/difficulty/due/reps/
     lapses/state) is kept? (e.g. the more-reviewed/most-recently-reviewed card; or meaning always; or
     the weaker of the two so nothing is over-scheduled.) Review logs of the absorbed card: reassign to
     the survivor or keep and mark? Effect on the FSRS optimizer.
   - Existing mechanism: cards have `merged_into_id` — can the absorbed card be tombstoned with it?
   - Must go through sync ops (CLAUDE.md rule 8): which op(s) (existing card.merge? new?), how devices
     apply them, offline devices holding unsynced reviews of an absorbed card (reviews logged later
     against it — remap on apply?).
   - Counts/limits after migration (new-card limit, keep going), deck membership, suspended/buried
     flags, blacklist/known overrides, jpdb re-import mapping.
4. Clients: web, Android, CLI, AnkiConnect/AnkiDroid stand-in — anything that assumes 2 cards/word.
5. Sibling burying becomes moot for vocab — remove or keep for other types.
6. Ticket split with deps and risks (contracts C-ticket, server migration/op, core builder/templates,
   Android apply, web/CLI), and what the user must do (if anything) — never anything that wipes
   unsynced device reviews.

## Deliverable
HANDOFF with: recommended design (≤ 400 words), ADR-035 draft text, ticket list. Read-only investigation
(prod read-only via API token GET / kubectl psql SELECT if auth works).

## Design accepted (ADR-035) — tickets
Design: plan/handoffs/ONE-CARD-design.md
1. C-28+ONE-CARD-CORE (architect; contracts/ + core/; high) — setting key docs, CardKinds.isRetired, generator, QueueBuilder, WordStatusRules, PendingEntry kind counts; sync scenario example (offline reading review after retirement); SPEC card-kinds table. Tests: builder golden (retired never served/counted/burying; entry draws only meaning), generator with deck card_kinds:["reading"].
2. ONE-CARD-SRV (server/; dep 1) — QueueLoader + materializer pass setting; settings validation bool; op apply of the key re-derives word statuses; stats/Today counts from builder.
3. ONE-CARD-AND (android/; dep 1) — LocalQueueLoader, apply hook re-derive, settings toggle "Also create reading cards" (i18n), add-card hides reading when off.
4. ONE-CARD-WEB (web/; dep 2) — settings toggle; add-card hides reading when off.
