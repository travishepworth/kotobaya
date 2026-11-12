# GrammarUpdatedSchemaSet

Registers to write. At least one.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**detection_rules** | [**List[GrammarCreatedSchemaDetectionRulesInner]**](GrammarCreatedSchemaDetectionRulesInner.md) | Register &#39;detection_rules&#39; (whole array). [] &#x3D; not auto-detected. | [optional] 
**explanation** | **str** | Register &#39;explanation&#39; (Markdown). | [optional] 
**is_stub** | **bool** | Register &#39;is_stub&#39;: true for seeded title-and-pattern-only points. | [optional] 
**jlpt** | **int** | Register &#39;jlpt&#39;: N-level 1-5, or null. | [optional] 
**links** | [**List[GrammarCreatedSchemaLinksInner]**](GrammarCreatedSchemaLinksInner.md) | Register &#39;links&#39; (whole array). | [optional] 
**meaning** | **str** | Register &#39;meaning&#39;. | [optional] 
**notes** | **str** | Register &#39;notes&#39; (Markdown). | [optional] 
**pattern** | **str** | Register &#39;pattern&#39;: display pattern, e.g. 〜ざるを得ない. | [optional] 
**related_ids** | **List[str]** | Register &#39;related_ids&#39;: soft references to other grammar points. | [optional] 
**title** | **str** | Register &#39;title&#39;. | [optional] 

## Example

```python
from kotobaya_client.models.grammar_updated_schema_set import GrammarUpdatedSchemaSet

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarUpdatedSchemaSet from a JSON string
grammar_updated_schema_set_instance = GrammarUpdatedSchemaSet.from_json(json)
# print the JSON string representation of the object
print(GrammarUpdatedSchemaSet.to_json())

# convert the object into a dict
grammar_updated_schema_set_dict = grammar_updated_schema_set_instance.to_dict()
# create an instance of GrammarUpdatedSchemaSet from a dict
grammar_updated_schema_set_from_dict = GrammarUpdatedSchemaSet.from_dict(grammar_updated_schema_set_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


