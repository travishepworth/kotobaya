# CardPatch

Changes to a card's user registers. At least one property.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**buried_until** | **datetime** | Bury until this instant, or null to unbury. | [optional] 
**flags** | **int** | Flag colour; 0 clears. | [optional] 
**preset_id** | **str** | A live preset. | [optional] 
**suspended** | **bool** | Suspend or unsuspend. | [optional] 

## Example

```python
from kotobaya_client.models.card_patch import CardPatch

# TODO update the JSON string below
json = "{}"
# create an instance of CardPatch from a JSON string
card_patch_instance = CardPatch.from_json(json)
# print the JSON string representation of the object
print(CardPatch.to_json())

# convert the object into a dict
card_patch_dict = card_patch_instance.to_dict()
# create an instance of CardPatch from a dict
card_patch_from_dict = CardPatch.from_dict(card_patch_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


