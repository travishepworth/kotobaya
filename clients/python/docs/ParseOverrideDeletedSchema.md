# ParseOverrideDeletedSchema

Tombstones a parse override (permanent). Server only: devices skip it. OPS.md#parse_overridedeleted

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the parse override. Equals the envelope&#39;s entity_id. | 

## Example

```python
from kotobaya_client.models.parse_override_deleted_schema import ParseOverrideDeletedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of ParseOverrideDeletedSchema from a JSON string
parse_override_deleted_schema_instance = ParseOverrideDeletedSchema.from_json(json)
# print the JSON string representation of the object
print(ParseOverrideDeletedSchema.to_json())

# convert the object into a dict
parse_override_deleted_schema_dict = parse_override_deleted_schema_instance.to_dict()
# create an instance of ParseOverrideDeletedSchema from a dict
parse_override_deleted_schema_from_dict = ParseOverrideDeletedSchema.from_dict(parse_override_deleted_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


