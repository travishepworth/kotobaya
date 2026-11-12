# HotWord

A word you are currently struggling with.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**cards** | [**List[Card]**](Card.md) | Live cards of the note (their &#x60;note&#x60; property is omitted). | 
**entry** | [**DictEntrySummary**](DictEntrySummary.md) | Dictionary summary, or null. | 
**entry_seq** | **int** | Its entry; null for unmatched vocab notes. | 
**failures_14d** | **int** | Again ratings on its cards in the last 14 days (scheduled and learning reviews). | 
**note** | [**NoteSummary**](NoteSummary.md) |  | 
**note_id** | **str** | The vocab note. | 
**score** | **float** | Hot-word score (core-defined scale; only the order is meaningful). | 

## Example

```python
from kotobaya_client.models.hot_word import HotWord

# TODO update the JSON string below
json = "{}"
# create an instance of HotWord from a JSON string
hot_word_instance = HotWord.from_json(json)
# print the JSON string representation of the object
print(HotWord.to_json())

# convert the object into a dict
hot_word_dict = hot_word_instance.to_dict()
# create an instance of HotWord from a dict
hot_word_from_dict = HotWord.from_dict(hot_word_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


