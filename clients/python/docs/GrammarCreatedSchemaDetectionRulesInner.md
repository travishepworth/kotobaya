# GrammarCreatedSchemaDetectionRulesInner

Unknown keys are preserved.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**draft** | **bool** | true &#x3D; not yet used for detection. | [optional] 
**rule** | **str** | Token-pattern rule. | 

## Example

```python
from kotobaya_client.models.grammar_created_schema_detection_rules_inner import GrammarCreatedSchemaDetectionRulesInner

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarCreatedSchemaDetectionRulesInner from a JSON string
grammar_created_schema_detection_rules_inner_instance = GrammarCreatedSchemaDetectionRulesInner.from_json(json)
# print the JSON string representation of the object
print(GrammarCreatedSchemaDetectionRulesInner.to_json())

# convert the object into a dict
grammar_created_schema_detection_rules_inner_dict = grammar_created_schema_detection_rules_inner_instance.to_dict()
# create an instance of GrammarCreatedSchemaDetectionRulesInner from a dict
grammar_created_schema_detection_rules_inner_from_dict = GrammarCreatedSchemaDetectionRulesInner.from_dict(grammar_created_schema_detection_rules_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


