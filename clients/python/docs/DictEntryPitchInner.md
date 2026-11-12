# DictEntryPitchInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**positions** | **List[int]** | Downstep mora positions, most common first; 0 &#x3D; heiban. | 
**reading** | **str** | Kana reading. | 

## Example

```python
from kotobaya_client.models.dict_entry_pitch_inner import DictEntryPitchInner

# TODO update the JSON string below
json = "{}"
# create an instance of DictEntryPitchInner from a JSON string
dict_entry_pitch_inner_instance = DictEntryPitchInner.from_json(json)
# print the JSON string representation of the object
print(DictEntryPitchInner.to_json())

# convert the object into a dict
dict_entry_pitch_inner_dict = dict_entry_pitch_inner_instance.to_dict()
# create an instance of DictEntryPitchInner from a dict
dict_entry_pitch_inner_from_dict = DictEntryPitchInner.from_dict(dict_entry_pitch_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


