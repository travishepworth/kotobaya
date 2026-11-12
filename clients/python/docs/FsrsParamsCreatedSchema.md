# FsrsParamsCreatedSchema

Creates a scheduling preset. Weights start NULL (core defaults), weights_version 0. OPS.md#fsrs_paramscreated

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**desired_retention** | **float** | Register &#39;desired_retention&#39;. Column default 0.9. | [optional] 
**easy_days** | **List[float]** | Register &#39;easy_days&#39;: load multiplier per ISO weekday, Monday first. Column default [1,1,1,1,1,1,1]. | [optional] 
**fuzz** | **bool** | Register &#39;fuzz&#39;. Column default true. | [optional] 
**id** | **str** | Id of the new preset. Equals the envelope&#39;s entity_id. | 
**learning_steps_s** | **List[int]** | Register &#39;learning_steps_s&#39;: steps in seconds, in order; [] &#x3D; none. | [optional] 
**maximum_interval_days** | **int** | Register &#39;maximum_interval_days&#39;. Column default 36500. | [optional] 
**name** | **str** | Register &#39;name&#39;. | 
**relearning_steps_s** | **List[int]** | Register &#39;relearning_steps_s&#39;: steps in seconds, in order; [] &#x3D; none. | [optional] 

## Example

```python
from kotobaya_client.models.fsrs_params_created_schema import FsrsParamsCreatedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of FsrsParamsCreatedSchema from a JSON string
fsrs_params_created_schema_instance = FsrsParamsCreatedSchema.from_json(json)
# print the JSON string representation of the object
print(FsrsParamsCreatedSchema.to_json())

# convert the object into a dict
fsrs_params_created_schema_dict = fsrs_params_created_schema_instance.to_dict()
# create an instance of FsrsParamsCreatedSchema from a dict
fsrs_params_created_schema_from_dict = FsrsParamsCreatedSchema.from_dict(fsrs_params_created_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


