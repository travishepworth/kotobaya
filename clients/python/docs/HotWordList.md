# HotWordList

Hot words.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[HotWord]**](HotWord.md) | Hottest first. | 

## Example

```python
from kotobaya_client.models.hot_word_list import HotWordList

# TODO update the JSON string below
json = "{}"
# create an instance of HotWordList from a JSON string
hot_word_list_instance = HotWordList.from_json(json)
# print the JSON string representation of the object
print(HotWordList.to_json())

# convert the object into a dict
hot_word_list_dict = hot_word_list_instance.to_dict()
# create an instance of HotWordList from a dict
hot_word_list_from_dict = HotWordList.from_dict(hot_word_list_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


