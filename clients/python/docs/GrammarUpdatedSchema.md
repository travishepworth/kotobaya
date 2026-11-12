# GrammarUpdatedSchema

Sets a subset of a grammar point's registers, all at the op's hlc. OPS.md#grammarupdated

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the grammar point. Equals the envelope&#39;s entity_id. | 
**set** | [**GrammarUpdatedSchemaSet**](GrammarUpdatedSchemaSet.md) |  | 

## Example

```python
from kotobaya_client.models.grammar_updated_schema import GrammarUpdatedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarUpdatedSchema from a JSON string
grammar_updated_schema_instance = GrammarUpdatedSchema.from_json(json)
# print the JSON string representation of the object
print(GrammarUpdatedSchema.to_json())

# convert the object into a dict
grammar_updated_schema_dict = grammar_updated_schema_instance.to_dict()
# create an instance of GrammarUpdatedSchema from a dict
grammar_updated_schema_from_dict = GrammarUpdatedSchema.from_dict(grammar_updated_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


