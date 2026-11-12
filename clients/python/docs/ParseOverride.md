# ParseOverride

A tokenisation correction (`app.parse_override`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**created_at** | **datetime** | Creation time. | 
**deleted_at** | **datetime** | Tombstone (permanent). | 
**id** | **str** | Override id. | 
**match_text** | **str** | Exact surface substring that is re-tokenised. | 
**media_id** | **str** | Scope - null &#x3D; all media. | 
**note** | **str** | Free-form remark. | 
**sentence_id** | **str** | Narrowest scope - only this sentence. | 
**tokens** | [**List[OverrideToken]**](OverrideToken.md) | Replacement tokens; surfaces concatenate to &#x60;match_text&#x60;. | 
**updated_at** | **datetime** | Last change. | 

## Example

```python
from kotobaya_client.models.parse_override import ParseOverride

# TODO update the JSON string below
json = "{}"
# create an instance of ParseOverride from a JSON string
parse_override_instance = ParseOverride.from_json(json)
# print the JSON string representation of the object
print(ParseOverride.to_json())

# convert the object into a dict
parse_override_dict = parse_override_instance.to_dict()
# create an instance of ParseOverride from a dict
parse_override_from_dict = ParseOverride.from_dict(parse_override_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


