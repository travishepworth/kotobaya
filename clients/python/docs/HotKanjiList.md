# HotKanjiList

Hot kanji.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[HotKanji]**](HotKanji.md) | Hottest first. | 

## Example

```python
from kotobaya_client.models.hot_kanji_list import HotKanjiList

# TODO update the JSON string below
json = "{}"
# create an instance of HotKanjiList from a JSON string
hot_kanji_list_instance = HotKanjiList.from_json(json)
# print the JSON string representation of the object
print(HotKanjiList.to_json())

# convert the object into a dict
hot_kanji_list_dict = hot_kanji_list_instance.to_dict()
# create an instance of HotKanjiList from a dict
hot_kanji_list_from_dict = HotKanjiList.from_dict(hot_kanji_list_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


