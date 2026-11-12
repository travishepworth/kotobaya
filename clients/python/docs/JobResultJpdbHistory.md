# JobResultJpdbHistory

Result of a jpdb history import. Every input record is counted in exactly one bucket (C-2, C-4; mapping rules on `importJpdbHistory`): - rows of the vocab arrays: `vocabulary_total = vocabulary_carded + vocabulary_status_only   + vocabulary_en_jp_ignored + len(unmapped)`; - rows of the kanji arrays: `kanji_total = kanji_carded + len(kanji_unmapped)`; - review events of all four arrays: `reviews_total = reviews_imported + reviews_already_imported   + reviews_skipped + reviews_unknown_grade + status_events_unknown + status_events_known   + reviews_duplicate_dropped`. An event's bucket is the first that applies: (1) its row is a `cards_vocabulary_en_jp` row or is unmapped → `reviews_skipped`; (2) malformed → `reviews_unknown_grade`; (3) exact duplicate within its group → `reviews_duplicate_dropped`; (4) grade `unknown` → `status_events_unknown`; (5) grade `known` → `status_events_known` (whether or not it set a word status); (6) any other non-rating grade → `reviews_unknown_grade`; (7) rating with `import_reviews = false` → `reviews_skipped`; (8) rating whose review id already exists → `reviews_already_imported`; (9) else `reviews_imported`. Rows count rows, not distinct words: rows that repeat a vid are each counted in `vocabulary_total` and in their group's bucket (`unmapped` lists each row). `notes_*`, `cards_created`, `kanji_notes_*`, `kanji_cards_*` and `word_statuses_set` count what was written, not input records. With `import_reviews = true`: `notes_created + notes_existing` = distinct seqs of carded vocab groups; `cards_created <= notes_created + notes_existing`; `word_statuses_set <=` distinct seqs of status-only groups; `kanji_notes_created + kanji_notes_existing` = distinct characters of mapped kanji rows; `kanji_cards_suspended <= kanji_cards_created`. An export without kanji arrays reports the kanji counts as 0.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**cards_created** | **int** | Vocabulary &#x60;meaning&#x60; cards created, at most one per carded group, so about equal to &#x60;notes_created&#x60; (kanji cards are &#x60;kanji_cards_created&#x60;). | 
**kanji_carded** | **int** | Kanji rows mapped to a kanji note and a card of kind &#x60;keyword&#x60; (char → keyword) or &#x60;writing&#x60; (keyword → char), known-only rows included. &#x60;writing&#x60; cards and known-only cards are created suspended. | 
**kanji_cards_created** | **int** | Kanji cards created (one per (character, kind) without a live card), suspended ones included. | 
**kanji_cards_suspended** | **int** | Of &#x60;kanji_cards_created&#x60;, those created with &#x60;suspended &#x3D; true&#x60; - every created &#x60;writing&#x60; card plus created &#x60;keyword&#x60; cards of known-only groups. Existing cards are never counted or changed. Added by C-4. | 
**kanji_notes_created** | **int** | Kanji notes created (one per character, shared by both arrays). | 
**kanji_notes_existing** | **int** | Distinct characters that already had a live kanji note. | 
**kanji_total** | **int** | Kanji records in the export (rows of &#x60;cards_kanji_char_keyword&#x60; and &#x60;cards_kanji_keyword_char&#x60;). | 
**kanji_unmapped** | [**List[JobResultJpdbHistoryKanjiUnmappedInner]**](JobResultJpdbHistoryKanjiUnmappedInner.md) | Kanji rows whose &#x60;character&#x60; is not exactly one Unicode code point. Their events count as &#x60;reviews_skipped&#x60;. | 
**notes_created** | **int** | Vocabulary notes created, one per carded group (distinct seq), not per row (kanji notes are &#x60;kanji_notes_created&#x60;). | 
**notes_existing** | **int** | Carded vocabulary groups (distinct seqs) that already had a live note (kanji - &#x60;kanji_notes_existing&#x60;). | 
**reviews_already_imported** | **int** | Reviews skipped because an earlier run imported them. | 
**reviews_duplicate_dropped** | **int** | Events of mapped rows dropped as exact duplicates - same &#x60;timestamp&#x60; and &#x60;grade&#x60; as an earlier event of the same group (vocab - rows sharing a seq; kanji - rows sharing character and card kind). Any grade, status events included. Added by C-4. | 
**reviews_imported** | **int** | Reviews logged by this run. | 
**reviews_skipped** | **int** | Events of unmapped rows (&#x60;unmapped&#x60;, &#x60;kanji_unmapped&#x60;) and of &#x60;cards_vocabulary_en_jp&#x60; rows (any grade), and rating events when &#x60;import_reviews&#x60; is false. | 
**reviews_total** | **int** | Review events in the export (every element of every row&#39;s &#x60;reviews[]&#x60;, all four arrays, status events included). | 
**reviews_unknown_grade** | **int** | Events of a mapped row that are malformed (not an object with an integer &#x60;timestamp&#x60; and a string &#x60;grade&#x60;) or whose &#x60;grade&#x60; is none of the known grades; nothing is written for them. | 
**status_events_known** | **int** | &#x60;known&#x60; events of mapped rows, duplicates excluded, never a review. All of them count here: those of status-only vocab groups (which may set a word status), those of carded vocab groups (ignored; FSRS schedules from the reviews) and those of kanji groups (a known-only kanji card is created suspended). | 
**status_events_unknown** | **int** | &#x60;unknown&#x60; events of mapped rows, duplicates excluded (card creation only, no review). | 
**unmapped** | [**List[JobResultJpdbHistoryUnmappedInner]**](JobResultJpdbHistoryUnmappedInner.md) | &#x60;cards_vocabulary_jp_en&#x60; rows that map to no JMdict entry, one element per row. | 
**vocabulary_carded** | **int** | Mapped &#x60;cards_vocabulary_jp_en&#x60; rows whose group (rows sharing the mapped seq) is carded - it has at least one kept non-&#x60;known&#x60; event, or no well-formed events; the group&#39;s note has a &#x60;meaning&#x60; card after the run (unless &#x60;import_reviews&#x60; is false). One note and one card per group, not per row. | 
**vocabulary_en_jp_ignored** | **int** | Rows of &#x60;cards_vocabulary_en_jp&#x60; (C-4). Never mapped, never listed in &#x60;unmapped&#x60;; no note, card, status or review; their events count as &#x60;reviews_skipped&#x60;. Added by C-4. | 
**vocabulary_status_only** | **int** | Mapped &#x60;cards_vocabulary_jp_en&#x60; rows whose group&#39;s kept well-formed events are all &#x60;known&#x60; (at least one); the word gets status &#x60;known&#x60; (unless it already has an override or &#x60;import_word_status&#x60; is false), no note or card. | 
**vocabulary_total** | **int** | Vocabulary records in the export (rows of &#x60;cards_vocabulary_jp_en&#x60; and &#x60;cards_vocabulary_en_jp&#x60;). | 
**word_statuses_set** | **int** | Overrides written (&#x60;word.status_set&#x60; ops emitted), at most one per status-only vocab group. | 

## Example

```python
from kotobaya_client.models.job_result_jpdb_history import JobResultJpdbHistory

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultJpdbHistory from a JSON string
job_result_jpdb_history_instance = JobResultJpdbHistory.from_json(json)
# print the JSON string representation of the object
print(JobResultJpdbHistory.to_json())

# convert the object into a dict
job_result_jpdb_history_dict = job_result_jpdb_history_instance.to_dict()
# create an instance of JobResultJpdbHistory from a dict
job_result_jpdb_history_from_dict = JobResultJpdbHistory.from_dict(job_result_jpdb_history_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


