# HANDOFF SRV-28

STATUS: DONE
BRANCH: t/SRV-28-server
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- QueueLoader: `requiredKanji`/`missingKanji` via new `KanjiNeeds` (core KanjiRequirement), companions in scoped builds, started vocab/kanji cards passed whatever their due date/suspension (backfill needs them)
- DeckMaterializer: step A mints `kanjiToMaterialize` (skips live/user-deleted), then step B; ReviewApi rounds + `to_materialize` count both; `loop.until_stop`
- `kanji_parts` (QueueExtras, batched), `front_furigana` + furigana text front (ReviewViews/QueueText); `Deck.progress` (DeckProgressLoader, 6 fixed statements)
- Goldens re-recorded (all expects held) + 11 new in `fixtures/shell-parity/golden`; tests adapted (loopSame, ref.kanji grades, lead semantics)
- Attempt 2: `DeckEntryPlan.prepare` loads blocked set + kanji data once per build (2 statements, not per word); tests: bounded-statement and suspended-due-review

## Acceptance → tests
- mint once/idempotent, kanji_parts, deleted not recreated → `KanjiFirstTest`; progress → `KanjiFirstTest.deckProgress…`; parity → `ParityGoldenTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Loader now passes every started vocab/kanji card (perf on big collections; unmeasured). No server test was pinned to seqs 1000220/440/450; android tests are not mine.
- Writes `fixtures/shell-parity/golden` (outside `server/**`) as the ticket requires.

## Backfill size (read-only; validated on a fixture DB, not live)
```sql
with words as (
  select n.id, coalesce(nullif(regexp_replace(coalesce(n.fields->>'expression',''),'<[^>]*>','','g'),''), de.headword) as expr, de.kanji_forms, de.senses
  from app.note n left join ref.dict_entry de on de.seq = n.entry_seq left join app.word_status ws on ws.entry_seq = n.entry_seq
  where n.type='vocab' and n.deleted_at is null and coalesce(ws.override_status,'') not in ('blacklisted','ignored')
    and exists (select 1 from app.card c where c.note_id=n.id and c.deleted_at is null and c.merged_into_id is null and c.state<>'new' and c.kind<>'reading')
), kept as (select w.* from words w where not (
  exists (select 1 from jsonb_array_elements(w.kanji_forms) kf, jsonb_array_elements_text(kf->'tags') t where kf->>'text'=w.expr and t in ('ateji','rK','oK','iK','sK'))
  or coalesce((select jsonb_exists(s.v->'misc','uk') from jsonb_array_elements(w.senses) with ordinality s(v,o)
     where s.v->'applies_to_kanji' @> '"*"'::jsonb or s.v->'applies_to_kanji' @> to_jsonb(w.expr) order by s.o limit 1), false)))
select count(distinct ch) from kept w, regexp_split_to_table(w.expr,'') ch join ref.kanji k on k.literal = ch
where (k.grade in (1,2,3,4,5,6,8) or k.freq_rank between 1 and 2500)
  and not exists (select 1 from app.note kn where kn.kanji_literal = ch and (kn.deleted_at is null or kn.merged_into_id is null));
```
(Assumes `cards.kanji_exemptions` on and reading cards off; ignores sibling-bury card counts.)
