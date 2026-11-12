# GrammarPoint

A grammar point (`app.grammar_point`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**created_at** | **datetime** | Creation time. | 
**deleted_at** | **datetime** | Tombstone. | 
**detection_rules** | [**List[DetectionRule]**](DetectionRule.md) | Detection rules; empty &#x3D; not auto-detected. | 
**example_count** | **int** | Derived - number of live spans (detected and manual) whose sentence exists. | 
**explanation** | **str** | Explanation (Markdown). | 
**id** | **str** | Grammar point id. | 
**is_stub** | **bool** | true for seeded pattern-and-title-only points not yet filled in. | 
**jlpt** | **int** | N-level; null if unclassified. | 
**language** | **str** | BCP 47; always &#x60;ja&#x60; in v1. | 
**links** | [**List[GrammarLink]**](GrammarLink.md) | Reference links. | 
**meaning** | **str** | Short meaning. | 
**note_id** | **str** | Derived - your live grammar note for this point, or null. | 
**notes** | **str** | Register and nuance notes (Markdown). | 
**pattern** | **str** | Display pattern. | 
**related_ids** | **List[str]** | Related grammar points (soft references; may be deleted). | 
**title** | **str** | Title. | 
**updated_at** | **datetime** | Last change. | 

## Example

```python
from kotobaya_client.models.grammar_point import GrammarPoint

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarPoint from a JSON string
grammar_point_instance = GrammarPoint.from_json(json)
# print the JSON string representation of the object
print(GrammarPoint.to_json())

# convert the object into a dict
grammar_point_dict = grammar_point_instance.to_dict()
# create an instance of GrammarPoint from a dict
grammar_point_from_dict = GrammarPoint.from_dict(grammar_point_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


