# ParseOverridePutSchemaTokensInner

Unknown keys are preserved.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**lemma** | **str** | Dictionary form. | [optional] 
**seq** | **int** | Entry; null &#x3D; force &#39;no entry&#39;. | [optional] 
**surface** | **str** | Token surface. | 

## Example

```python
from kotobaya_client.models.parse_override_put_schema_tokens_inner import ParseOverridePutSchemaTokensInner

# TODO update the JSON string below
json = "{}"
# create an instance of ParseOverridePutSchemaTokensInner from a JSON string
parse_override_put_schema_tokens_inner_instance = ParseOverridePutSchemaTokensInner.from_json(json)
# print the JSON string representation of the object
print(ParseOverridePutSchemaTokensInner.to_json())

# convert the object into a dict
parse_override_put_schema_tokens_inner_dict = parse_override_put_schema_tokens_inner_instance.to_dict()
# create an instance of ParseOverridePutSchemaTokensInner from a dict
parse_override_put_schema_tokens_inner_from_dict = ParseOverridePutSchemaTokensInner.from_dict(parse_override_put_schema_tokens_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


