# GrammarDeletedSchema

Tombstones a grammar point (permanent). OPS.md#grammardeleted

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the grammar point. Equals the envelope&#39;s entity_id. | 

## Example

```python
from kotobaya_client.models.grammar_deleted_schema import GrammarDeletedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarDeletedSchema from a JSON string
grammar_deleted_schema_instance = GrammarDeletedSchema.from_json(json)
# print the JSON string representation of the object
print(GrammarDeletedSchema.to_json())

# convert the object into a dict
grammar_deleted_schema_dict = grammar_deleted_schema_instance.to_dict()
# create an instance of GrammarDeletedSchema from a dict
grammar_deleted_schema_from_dict = GrammarDeletedSchema.from_dict(grammar_deleted_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


