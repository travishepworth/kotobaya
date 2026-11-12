# DictEntryReadingsInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**applies_to** | **List[str]** | Kanji forms the reading applies to, or &#x60;[\&quot;*\&quot;]&#x60;. | 
**common** | **bool** | Common marker. | 
**tags** | **List[str]** | JMdict re_inf tags. | 
**text** | **str** | Kana reading. | 

## Example

```python
from kotobaya_client.models.dict_entry_readings_inner import DictEntryReadingsInner

# TODO update the JSON string below
json = "{}"
# create an instance of DictEntryReadingsInner from a JSON string
dict_entry_readings_inner_instance = DictEntryReadingsInner.from_json(json)
# print the JSON string representation of the object
print(DictEntryReadingsInner.to_json())

# convert the object into a dict
dict_entry_readings_inner_dict = dict_entry_readings_inner_instance.to_dict()
# create an instance of DictEntryReadingsInner from a dict
dict_entry_readings_inner_from_dict = DictEntryReadingsInner.from_dict(dict_entry_readings_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


