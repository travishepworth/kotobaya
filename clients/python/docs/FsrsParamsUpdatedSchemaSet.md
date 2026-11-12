# FsrsParamsUpdatedSchemaSet

Registers to write. At least one.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**desired_retention** | **float** | Register &#39;desired_retention&#39;. Column default 0.9. | [optional] 
**easy_days** | **List[float]** | Register &#39;easy_days&#39;: load multiplier per ISO weekday, Monday first. Column default [1,1,1,1,1,1,1]. | [optional] 
**fuzz** | **bool** | Register &#39;fuzz&#39;. Column default true. | [optional] 
**learning_steps_s** | **List[int]** | Register &#39;learning_steps_s&#39;: steps in seconds, in order; [] &#x3D; none. | [optional] 
**maximum_interval_days** | **int** | Register &#39;maximum_interval_days&#39;. Column default 36500. | [optional] 
**name** | **str** | Register &#39;name&#39;. | [optional] 
**relearning_steps_s** | **List[int]** | Register &#39;relearning_steps_s&#39;: steps in seconds, in order; [] &#x3D; none. | [optional] 

## Example

```python
from kotobaya_client.models.fsrs_params_updated_schema_set import FsrsParamsUpdatedSchemaSet

# TODO update the JSON string below
json = "{}"
# create an instance of FsrsParamsUpdatedSchemaSet from a JSON string
fsrs_params_updated_schema_set_instance = FsrsParamsUpdatedSchemaSet.from_json(json)
# print the JSON string representation of the object
print(FsrsParamsUpdatedSchemaSet.to_json())

# convert the object into a dict
fsrs_params_updated_schema_set_dict = fsrs_params_updated_schema_set_instance.to_dict()
# create an instance of FsrsParamsUpdatedSchemaSet from a dict
fsrs_params_updated_schema_set_from_dict = FsrsParamsUpdatedSchemaSet.from_dict(fsrs_params_updated_schema_set_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


