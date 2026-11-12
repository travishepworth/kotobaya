# HotKanji

A kanji you are currently struggling with.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**literal** | **str** | The kanji. | 
**meanings** | **List[str]** | English meanings; empty when the kanji is not in &#x60;ref.kanji&#x60;. | 
**note_id** | **str** | Your live kanji note, or null. | 
**score** | **float** | Roll-up of hot-word scores (core-defined scale; only the order is meaningful). | 
**word_count** | **int** | Number of hot words containing it. | 

## Example

```python
from kotobaya_client.models.hot_kanji import HotKanji

# TODO update the JSON string below
json = "{}"
# create an instance of HotKanji from a JSON string
hot_kanji_instance = HotKanji.from_json(json)
# print the JSON string representation of the object
print(HotKanji.to_json())

# convert the object into a dict
hot_kanji_dict = hot_kanji_instance.to_dict()
# create an instance of HotKanji from a dict
hot_kanji_from_dict = HotKanji.from_dict(hot_kanji_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


