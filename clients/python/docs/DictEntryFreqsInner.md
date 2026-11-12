# DictEntryFreqsInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**list_id** | **str** | Frequency list slug. | 
**rank** | **int** | 1 &#x3D; most frequent. | 

## Example

```python
from kotobaya_client.models.dict_entry_freqs_inner import DictEntryFreqsInner

# TODO update the JSON string below
json = "{}"
# create an instance of DictEntryFreqsInner from a JSON string
dict_entry_freqs_inner_instance = DictEntryFreqsInner.from_json(json)
# print the JSON string representation of the object
print(DictEntryFreqsInner.to_json())

# convert the object into a dict
dict_entry_freqs_inner_dict = dict_entry_freqs_inner_instance.to_dict()
# create an instance of DictEntryFreqsInner from a dict
dict_entry_freqs_inner_from_dict = DictEntryFreqsInner.from_dict(dict_entry_freqs_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


