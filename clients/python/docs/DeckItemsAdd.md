# DeckItemsAdd

Items to add to a user deck.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[DeckItemsAddItemsInner]**](DeckItemsAddItemsInner.md) | Items in the order to append them. | 

## Example

```python
from kotobaya_client.models.deck_items_add import DeckItemsAdd

# TODO update the JSON string below
json = "{}"
# create an instance of DeckItemsAdd from a JSON string
deck_items_add_instance = DeckItemsAdd.from_json(json)
# print the JSON string representation of the object
print(DeckItemsAdd.to_json())

# convert the object into a dict
deck_items_add_dict = deck_items_add_instance.to_dict()
# create an instance of DeckItemsAdd from a dict
deck_items_add_from_dict = DeckItemsAdd.from_dict(deck_items_add_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


