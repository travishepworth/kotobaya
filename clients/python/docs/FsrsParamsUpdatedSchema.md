# FsrsParamsUpdatedSchema

Sets a subset of a preset's registers. A winning write to any register except 'name' forces a replay of every card on the preset. OPS.md#fsrs_paramsupdated

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the preset. Equals the envelope&#39;s entity_id. | 
**set** | [**FsrsParamsUpdatedSchemaSet**](FsrsParamsUpdatedSchemaSet.md) |  | 

## Example

```python
from kotobaya_client.models.fsrs_params_updated_schema import FsrsParamsUpdatedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of FsrsParamsUpdatedSchema from a JSON string
fsrs_params_updated_schema_instance = FsrsParamsUpdatedSchema.from_json(json)
# print the JSON string representation of the object
print(FsrsParamsUpdatedSchema.to_json())

# convert the object into a dict
fsrs_params_updated_schema_dict = fsrs_params_updated_schema_instance.to_dict()
# create an instance of FsrsParamsUpdatedSchema from a dict
fsrs_params_updated_schema_from_dict = FsrsParamsUpdatedSchema.from_dict(fsrs_params_updated_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


