# JobResultJpdbHistory

Result of a jpdb history import. Every input record is counted in exactly one bucket (C-2; mapping rules on `importJpdbHistory`): - rows of the vocab arrays: `vocabulary_total = vocabulary_carded + vocabulary_status_only + len(unmapped)`; - rows of the kanji arrays: `kanji_total = kanji_carded + len(kanji_unmapped)`; - review events of all four arrays: `reviews_total = reviews_imported + reviews_already_imported   + reviews_skipped + reviews_unknown_grade + status_events_unknown + status_events_known`. `notes_*`, `cards_created`, `kanji_notes_*`, `kanji_cards_created` and `word_statuses_set` count what was written, not input records. An export without kanji arrays reports the kanji counts as 0.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**cards_created** | **int** | Vocabulary cards created (kanji cards are &#x60;kanji_cards_created&#x60;). | 
**kanji_carded** | **int** | Kanji rows mapped to a kanji note and a card of kind &#x60;keyword&#x60; (char → keyword) or &#x60;writing&#x60; (keyword → char). | 
**kanji_cards_created** | **int** | Kanji cards created. | 
**kanji_notes_created** | **int** | Kanji notes created (one per character, shared by both arrays). | 
**kanji_notes_existing** | **int** | Distinct characters that already had a live kanji note. | 
**kanji_total** | **int** | Kanji records in the export (rows of &#x60;cards_kanji_char_keyword&#x60; and &#x60;cards_kanji_keyword_char&#x60;). | 
**kanji_unmapped** | [**List[JobResultJpdbHistoryKanjiUnmappedInner]**](JobResultJpdbHistoryKanjiUnmappedInner.md) | Kanji rows whose &#x60;character&#x60; is not exactly one Unicode code point. Their events count as &#x60;reviews_skipped&#x60;. | 
**notes_created** | **int** | Vocabulary notes created (kanji notes are &#x60;kanji_notes_created&#x60;). | 
**notes_existing** | **int** | Mapped vocabulary that already had a live note (kanji - &#x60;kanji_notes_existing&#x60;). | 
**reviews_already_imported** | **int** | Reviews skipped because an earlier run imported them. | 
**reviews_imported** | **int** | Reviews logged by this run. | 
**reviews_skipped** | **int** | Events of unmapped rows (&#x60;unmapped&#x60;, &#x60;kanji_unmapped&#x60;), and rating events when &#x60;import_reviews&#x60; is false. | 
**reviews_total** | **int** | Review events in the export (every element of every row&#39;s &#x60;reviews[]&#x60;, all four arrays, status events included). | 
**reviews_unknown_grade** | **int** | Events of a mapped row whose &#x60;grade&#x60; is none of the known grades; nothing is written for them. | 
**status_events_known** | **int** | &#x60;known&#x60; events of mapped rows (vocab - a word status; kanji - nothing beyond the note and card; never a review). | 
**status_events_unknown** | **int** | &#x60;unknown&#x60; events of mapped rows (card creation only, no review). | 
**unmapped** | [**List[JobResultJpdbHistoryUnmappedInner]**](JobResultJpdbHistoryUnmappedInner.md) | Vocabulary that maps to no JMdict entry. | 
**vocabulary_carded** | **int** | Mapped vocabulary rows with at least one event other than &#x60;known&#x60;; each has a note and a card after the run (unless &#x60;import_reviews&#x60; is false). | 
**vocabulary_status_only** | **int** | Mapped vocabulary rows whose events are all &#x60;known&#x60;; they get a word status only, no note or card. | 
**vocabulary_total** | **int** | Vocabulary records in the export (rows of &#x60;cards_vocabulary_jp_en&#x60; and &#x60;cards_vocabulary_en_jp&#x60;). | 
**word_statuses_set** | **int** | Overrides written (&#x60;word.status_set&#x60; ops emitted). | 

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


