# PresetList

Presets.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[Preset]**](Preset.md) | Presets, Default first. | 

## Example

```python
from kotobaya_client.models.preset_list import PresetList

# TODO update the JSON string below
json = "{}"
# create an instance of PresetList from a JSON string
preset_list_instance = PresetList.from_json(json)
# print the JSON string representation of the object
print(PresetList.to_json())

# convert the object into a dict
preset_list_dict = preset_list_instance.to_dict()
# create an instance of PresetList from a dict
preset_list_from_dict = PresetList.from_dict(preset_list_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


