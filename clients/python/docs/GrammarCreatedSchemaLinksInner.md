# GrammarCreatedSchemaLinksInner

Unknown keys are preserved.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**title** | **str** | Link text. | 
**url** | **str** | URL. | 

## Example

```python
from kotobaya_client.models.grammar_created_schema_links_inner import GrammarCreatedSchemaLinksInner

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarCreatedSchemaLinksInner from a JSON string
grammar_created_schema_links_inner_instance = GrammarCreatedSchemaLinksInner.from_json(json)
# print the JSON string representation of the object
print(GrammarCreatedSchemaLinksInner.to_json())

# convert the object into a dict
grammar_created_schema_links_inner_dict = grammar_created_schema_links_inner_instance.to_dict()
# create an instance of GrammarCreatedSchemaLinksInner from a dict
grammar_created_schema_links_inner_from_dict = GrammarCreatedSchemaLinksInner.from_dict(grammar_created_schema_links_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


