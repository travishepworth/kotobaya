# CardBulkAction

The action of a bulk call. Which params are required depends on `type` (table on `POST /cards/bulk`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**flags** | **int** | &#x60;flag&#x60; - the flag value. | [optional] 
**from_deck_id** | **str** | &#x60;move&#x60; - user deck to remove the items from. | [optional] 
**preset_id** | **str** | &#x60;set_preset&#x60; - the preset. | [optional] 
**tags** | **List[str]** | &#x60;add_tags&#x60; / &#x60;remove_tags&#x60; - the tags. | [optional] 
**to_deck_id** | **str** | &#x60;move&#x60; - destination user deck. | [optional] 
**type** | **str** | What to do. | 
**until** | **datetime** | &#x60;bury&#x60; - bury until this instant (must be in the future). | [optional] 

## Example

```python
from kotobaya_client.models.card_bulk_action import CardBulkAction

# TODO update the JSON string below
json = "{}"
# create an instance of CardBulkAction from a JSON string
card_bulk_action_instance = CardBulkAction.from_json(json)
# print the JSON string representation of the object
print(CardBulkAction.to_json())

# convert the object into a dict
card_bulk_action_dict = card_bulk_action_instance.to_dict()
# create an instance of CardBulkAction from a dict
card_bulk_action_from_dict = CardBulkAction.from_dict(card_bulk_action_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


