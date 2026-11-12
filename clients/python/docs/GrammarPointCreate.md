# GrammarPointCreate

New grammar point.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**detection_rules** | [**List[DetectionRule]**](DetectionRule.md) | Detection rules. | [optional] [default to []]
**explanation** | **str** | Explanation (Markdown). | [optional] [default to '']
**id** | **str** | Optional client-generated UUIDv7 (idempotent creation). | [optional] 
**is_stub** | **bool** | Mark as a stub. | [optional] [default to False]
**jlpt** | **int** | N-level. | [optional] 
**links** | [**List[GrammarLink]**](GrammarLink.md) | Reference links. | [optional] [default to []]
**meaning** | **str** | Short meaning. | [optional] [default to '']
**notes** | **str** | Register and nuance notes (Markdown). | [optional] [default to '']
**pattern** | **str** | Display pattern. | [optional] [default to '']
**related_ids** | **List[str]** | Related points. | [optional] [default to []]
**title** | **str** | Title. | 

## Example

```python
from kotobaya_client.models.grammar_point_create import GrammarPointCreate

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarPointCreate from a JSON string
grammar_point_create_instance = GrammarPointCreate.from_json(json)
# print the JSON string representation of the object
print(GrammarPointCreate.to_json())

# convert the object into a dict
grammar_point_create_dict = grammar_point_create_instance.to_dict()
# create an instance of GrammarPointCreate from a dict
grammar_point_create_from_dict = GrammarPointCreate.from_dict(grammar_point_create_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


