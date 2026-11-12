# CardMergedSchema

Server verdict that two live cards had the same (note, kind), or that a card of a merged note is folded into the survivor's card. OPS.md#cardmerged

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**loser_id** | **str** | Card that is tombstoned and gets merged_into_id &#x3D; survivor_id. Equals entity_id. | 
**survivor_id** | **str** | Card whose replay set now includes the loser&#39;s reviews. Differs from loser_id. | 

## Example

```python
from kotobaya_client.models.card_merged_schema import CardMergedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of CardMergedSchema from a JSON string
card_merged_schema_instance = CardMergedSchema.from_json(json)
# print the JSON string representation of the object
print(CardMergedSchema.to_json())

# convert the object into a dict
card_merged_schema_dict = card_merged_schema_instance.to_dict()
# create an instance of CardMergedSchema from a dict
card_merged_schema_from_dict = CardMergedSchema.from_dict(card_merged_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


