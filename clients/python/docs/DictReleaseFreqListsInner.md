# DictReleaseFreqListsInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**entry_count** | **int** | Entries ranked by the list. | 
**id** | **str** | Slug. | 
**priority** | **int** | 0 &#x3D; primary list. | 
**title** | **str** | Display title. | 

## Example

```python
from kotobaya_client.models.dict_release_freq_lists_inner import DictReleaseFreqListsInner

# TODO update the JSON string below
json = "{}"
# create an instance of DictReleaseFreqListsInner from a JSON string
dict_release_freq_lists_inner_instance = DictReleaseFreqListsInner.from_json(json)
# print the JSON string representation of the object
print(DictReleaseFreqListsInner.to_json())

# convert the object into a dict
dict_release_freq_lists_inner_dict = dict_release_freq_lists_inner_instance.to_dict()
# create an instance of DictReleaseFreqListsInner from a dict
dict_release_freq_lists_inner_from_dict = DictReleaseFreqListsInner.from_dict(dict_release_freq_lists_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


