# JobResultJpdbHistoryKanjiUnmappedInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**card_kind** | **str** | &#x60;keyword&#x60; for &#x60;cards_kanji_char_keyword&#x60;, &#x60;writing&#x60; for &#x60;cards_kanji_keyword_char&#x60;. | 
**character** | **str** | The &#x60;character&#x60; value as given. | 
**review_count** | **int** | Events this record had. | 

## Example

```python
from kotobaya_client.models.job_result_jpdb_history_kanji_unmapped_inner import JobResultJpdbHistoryKanjiUnmappedInner

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultJpdbHistoryKanjiUnmappedInner from a JSON string
job_result_jpdb_history_kanji_unmapped_inner_instance = JobResultJpdbHistoryKanjiUnmappedInner.from_json(json)
# print the JSON string representation of the object
print(JobResultJpdbHistoryKanjiUnmappedInner.to_json())

# convert the object into a dict
job_result_jpdb_history_kanji_unmapped_inner_dict = job_result_jpdb_history_kanji_unmapped_inner_instance.to_dict()
# create an instance of JobResultJpdbHistoryKanjiUnmappedInner from a dict
job_result_jpdb_history_kanji_unmapped_inner_from_dict = JobResultJpdbHistoryKanjiUnmappedInner.from_dict(job_result_jpdb_history_kanji_unmapped_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


