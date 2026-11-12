# PresetPatch

Preset options to change. At least one property.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**desired_retention** | **float** | Target recall probability. | [optional] 
**easy_days** | **List[float]** | Load multiplier Monday..Sunday. | [optional] 
**fuzz** | **bool** | Apply fuzz and load balancing. | [optional] 
**learning_steps_s** | **List[int]** | Learning steps in seconds. | [optional] 
**maximum_interval_days** | **int** | Cap on intervals. | [optional] 
**name** | **str** | Display name. | [optional] 
**relearning_steps_s** | **List[int]** | Relearning steps in seconds. | [optional] 

## Example

```python
from kotobaya_client.models.preset_patch import PresetPatch

# TODO update the JSON string below
json = "{}"
# create an instance of PresetPatch from a JSON string
preset_patch_instance = PresetPatch.from_json(json)
# print the JSON string representation of the object
print(PresetPatch.to_json())

# convert the object into a dict
preset_patch_dict = preset_patch_instance.to_dict()
# create an instance of PresetPatch from a dict
preset_patch_from_dict = PresetPatch.from_dict(preset_patch_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


