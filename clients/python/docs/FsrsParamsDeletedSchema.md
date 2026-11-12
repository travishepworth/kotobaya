# FsrsParamsDeletedSchema

Tombstones a preset; its cards then schedule with Default. Rejected for the Default preset. OPS.md#fsrs_paramsdeleted

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the preset. Equals the envelope&#39;s entity_id. | 

## Example

```python
from kotobaya_client.models.fsrs_params_deleted_schema import FsrsParamsDeletedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of FsrsParamsDeletedSchema from a JSON string
fsrs_params_deleted_schema_instance = FsrsParamsDeletedSchema.from_json(json)
# print the JSON string representation of the object
print(FsrsParamsDeletedSchema.to_json())

# convert the object into a dict
fsrs_params_deleted_schema_dict = fsrs_params_deleted_schema_instance.to_dict()
# create an instance of FsrsParamsDeletedSchema from a dict
fsrs_params_deleted_schema_from_dict = FsrsParamsDeletedSchema.from_dict(fsrs_params_deleted_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


