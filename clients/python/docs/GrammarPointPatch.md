# GrammarPointPatch

Changes to a grammar point. At least one property.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**detection_rules** | [**List[DetectionRule]**](DetectionRule.md) | The whole new rule list. | [optional] 
**explanation** | **str** | Explanation (Markdown). | [optional] 
**is_stub** | **bool** | Stub marker. | [optional] 
**jlpt** | **int** | N-level; null &#x3D; unclassified. | [optional] 
**links** | [**List[GrammarLink]**](GrammarLink.md) | The whole new list. | [optional] 
**meaning** | **str** | Short meaning. | [optional] 
**notes** | **str** | Notes (Markdown). | [optional] 
**pattern** | **str** | Display pattern. | [optional] 
**related_ids** | **List[str]** | The whole new list. | [optional] 
**title** | **str** | Title. | [optional] 

## Example

```python
from kotobaya_client.models.grammar_point_patch import GrammarPointPatch

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarPointPatch from a JSON string
grammar_point_patch_instance = GrammarPointPatch.from_json(json)
# print the JSON string representation of the object
print(GrammarPointPatch.to_json())

# convert the object into a dict
grammar_point_patch_dict = grammar_point_patch_instance.to_dict()
# create an instance of GrammarPointPatch from a dict
grammar_point_patch_from_dict = GrammarPointPatch.from_dict(grammar_point_patch_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


