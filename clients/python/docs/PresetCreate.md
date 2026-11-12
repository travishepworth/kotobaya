# PresetCreate

New preset. Omitted options take the defaults shown.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**desired_retention** | **float** | Target recall probability. | [optional] [default to 0.9]
**easy_days** | **List[float]** | Load multiplier Monday..Sunday. | [optional] [default to [1, 1, 1, 1, 1, 1, 1]]
**fuzz** | **bool** | Apply fuzz and load balancing. | [optional] [default to True]
**id** | **str** | Optional client-generated UUIDv7 (idempotent creation). | [optional] 
**learning_steps_s** | **List[int]** | Learning steps in seconds. | [optional] [default to [60, 600]]
**maximum_interval_days** | **int** | Cap on intervals. | [optional] [default to 36500]
**name** | **str** | Display name. | 
**relearning_steps_s** | **List[int]** | Relearning steps in seconds. | [optional] [default to [600]]

## Example

```python
from kotobaya_client.models.preset_create import PresetCreate

# TODO update the JSON string below
json = "{}"
# create an instance of PresetCreate from a JSON string
preset_create_instance = PresetCreate.from_json(json)
# print the JSON string representation of the object
print(PresetCreate.to_json())

# convert the object into a dict
preset_create_dict = preset_create_instance.to_dict()
# create an instance of PresetCreate from a dict
preset_create_from_dict = PresetCreate.from_dict(preset_create_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


