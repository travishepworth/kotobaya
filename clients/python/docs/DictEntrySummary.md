# DictEntrySummary

Short form of a dictionary entry with the caller's overlay.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**freq_rank** | **int** | Rank in the primary frequency list, or null. | 
**glosses** | **List[str]** | English glosses of the first sense, in order. | 
**headword** | **str** | First kanji form, or first reading for kana-only entries. | 
**headword_reading** | **str** | First reading. | 
**is_common** | **bool** | JMdict \&quot;common\&quot; marker. | 
**note_id** | **str** | Your live vocab note for this entry, or null. | 
**pos** | **List[str]** | Union of JMdict part-of-speech tags. | 
**retired** | **bool** | The entry is no longer in JMdict. | 
**seq** | **int** | JMdict sequence number (&#x60;ref.dict_entry.seq&#x60;). | 
**status** | [**WordStatusValue**](WordStatusValue.md) | Your effective word status (&#x60;new&#x60; when there is no row). | 

## Example

```python
from kotobaya_client.models.dict_entry_summary import DictEntrySummary

# TODO update the JSON string below
json = "{}"
# create an instance of DictEntrySummary from a JSON string
dict_entry_summary_instance = DictEntrySummary.from_json(json)
# print the JSON string representation of the object
print(DictEntrySummary.to_json())

# convert the object into a dict
dict_entry_summary_dict = dict_entry_summary_instance.to_dict()
# create an instance of DictEntrySummary from a dict
dict_entry_summary_from_dict = DictEntrySummary.from_dict(dict_entry_summary_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


