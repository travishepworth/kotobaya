# DeckItemsAddItemsInner

Exactly one of `entry_seq` and `note_id`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**entry_seq** | **int** | A dictionary entry. | [optional] 
**note_id** | **str** | A note. | [optional] 
**position** | **int** | Explicit position. | [optional] 

## Example

```python
from kotobaya_client.models.deck_items_add_items_inner import DeckItemsAddItemsInner

# TODO update the JSON string below
json = "{}"
# create an instance of DeckItemsAddItemsInner from a JSON string
deck_items_add_items_inner_instance = DeckItemsAddItemsInner.from_json(json)
# print the JSON string representation of the object
print(DeckItemsAddItemsInner.to_json())

# convert the object into a dict
deck_items_add_items_inner_dict = deck_items_add_items_inner_instance.to_dict()
# create an instance of DeckItemsAddItemsInner from a dict
deck_items_add_items_inner_from_dict = DeckItemsAddItemsInner.from_dict(deck_items_add_items_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


