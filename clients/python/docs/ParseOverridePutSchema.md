# ParseOverridePutSchema

Creates or replaces a tokenisation correction (whole row, last-writer-wins on hlc). Server only: devices skip it. OPS.md#parse_overrideput

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the parse override. Equals the envelope&#39;s entity_id. | 
**match_text** | **str** | Exact surface substring this override re-tokenises. | 
**media_id** | **str** | Scope: null &#x3D; all media. Soft reference. Default null. | [optional] 
**note** | **str** | Free-text remark. Default &#39;&#39;. | [optional] [default to '']
**sentence_id** | **str** | Narrowest scope: one sentence; requires media_id. Soft reference. Default null. | [optional] 
**tokens** | [**List[ParseOverridePutSchemaTokensInner]**](ParseOverridePutSchemaTokensInner.md) | Replacement tokens; surfaces must concatenate to match_text (server rejects otherwise). | 

## Example

```python
from kotobaya_client.models.parse_override_put_schema import ParseOverridePutSchema

# TODO update the JSON string below
json = "{}"
# create an instance of ParseOverridePutSchema from a JSON string
parse_override_put_schema_instance = ParseOverridePutSchema.from_json(json)
# print the JSON string representation of the object
print(ParseOverridePutSchema.to_json())

# convert the object into a dict
parse_override_put_schema_dict = parse_override_put_schema_instance.to_dict()
# create an instance of ParseOverridePutSchema from a dict
parse_override_put_schema_from_dict = ParseOverridePutSchema.from_dict(parse_override_put_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


