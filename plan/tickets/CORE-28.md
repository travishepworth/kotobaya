# CORE-28 — Core: until_stop, kanji-first ordering, DeckProgress, KanjiParts

- Phase: P10 · Model: opus (writer-senior) · Risk: high
- Deps: C-28, C-28b
- Branch: t/CORE-28-core
- Owned paths: core/** (not core/src/commonMain/kotlin/kotobaya/contracts/**)
- Module(s) for CI: core (Gradle lock)

## Read
- Decisions: ADR-037..041. Contracts: everything C-28 changed (see its HANDOFF in plan/handoffs/C-28.md).

## Do
Implement C-28 in core: `ReviewLoop.untilStop`; kanji-first group rule in DefaultQueueBuilder (group for every source, atomic, mix never splits it, backfill order, missing kanji -> needs-materialize); DeckProgress calculator; KanjiParts builder (used reading alignment); ADR-042 KanjiRequirement predicate (word-level JMdict tags, learnable = jōyō ∪ freq top 2500) used by kanji-first, materialize, backfill and front furigana rendering. Export to JS build for web.
Known causes from scouting (DefaultQueueBuilder): group only when drawing source has add_kanji=BEFORE (turn() ~L421, kanjiGroup() ~L372); kanjiByLiteral skips Home.None (~L348); mix() (~L208) inserts reviews between group members; CardGenerator L95-105 only makes kanji notes at vocab creation.

ADR-042 front furigana: the HTML card render must use <ruby>word<rt>reading</rt></ruby> (web currently gets `寿司（すし）` text from core); plain-text render (QueueItem.text, CLI) may keep the parenthesised form.
## Acceptance
- [ ] All new parity scenarios pass; existing goldens/FSRS tests unchanged.
- [ ] Tests: deckless vocab gets kanji first; 40-new budget includes kanji; mix never splits group; backfill; untilStop grows on Again; ADR-042: 海老 exempt + furigana, 林檎 needs 林 only/no furigana, 薔薇 furigana/no kanji, 今日 no furigana; key off = old behaviour. KanjiParts used reading for 3 real words (e.g. 大学, 今日 → null/irregular, 食べる).
