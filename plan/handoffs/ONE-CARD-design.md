# ONE-CARD design (architect, 2026-11-16) — accepted as ADR-035

## Live data (GET /cards?query=kind:…, 2026-11-16)
- reading cards: 349 live (316 new, 24 review, 9 learning, 6 suspended); 296 created 11-15 + 50 on 11-16 → deck materialization (ADR-032).
- 33 reading cards have reviews (39 reviews); 31 of those pairs had the meaning sibling also reviewed; 2 reading-only. 9 due by tomorrow.
- meaning cards: 6939 (6711 reviewed). 6590 vocab notes have no reading card (jpdb imports, ADR-009). Every reading card has a meaning sibling.

## Design
- Scope: only built-in Vocab makes two default cards (meaning + reading). Kanji (keyword), Phrase (meaning), Sentence, Grammar, Custom already make one. Applies to cards of kind `reading` whose note base_type = vocab (incl. custom vocab types).
- Source of truth: synced setting `cards.reading_enabled` (bool, default false) via settings.set (like ADR-034). retired ⇔ kind = reading ∧ base_type = vocab ∧ setting false. One shared predicate in core: `CardKinds.isRetired`.
  - Card creation (CardGenerator; server + device materialization; createNote): never creates a retired kind — overrides template default and deck `card_kinds`.
  - QueueBuilder: retired card never a candidate (not served, counted, drawn into new-card groups, doesn't bury its sibling).
  - auto_status: retired cards excluded (else a reading card stuck in learning keeps word at LEARNING). Every replica re-derives vocab word statuses when it applies settings.set of this key.
- Retiring existing cards: the setting is a filter; no card ops. Rejected: mass card.suspended (unretire would unsuspend user's 6; server must keep suspending old-client creations), card.deleted (permanent), new kind (card.kind immutable). Idempotent, no migration, reversible by flipping.
- Unsynced device reviews: review.logged for a reading card accepted as usual, cache replays, card stays hidden. Nothing dropped/remapped. Old clients may still create reading cards; hidden on updated clients and in every server-built queue.
- History: reading reviews never feed the meaning card (merge = double same-day reviews; merges permanent SYNC.md 8.3). Stay in review_log, stats, optimizer (39 reviews).
- Turning it on: same old cards return with history+schedule. No new generation (would collide → merge). Notes created while off get no reading card (ADR-011, no backfill).
- Template: no change; meaning back already shows furigana, pitch, meaning (BuiltInTemplates.kt:28). Front = plain kanji expression.
- Sibling burying: keep (kanji keyword/writing, optional vocab kinds).
- Clients: web + CLI use server queue → fixed by server deploy. Android has local builder → new APK. AnkiConnect stand-in creates via CardGenerator. Browse / `kind:reading` search still list retired cards (unlabelled). Add-card UIs hide `reading` while off.

## Tickets
See plan/tickets/ONE-CARD.md (C-28+ONE-CARD-CORE → ONE-CARD-SRV → ONE-CARD-WEB; ONE-CARD-AND after CORE).
