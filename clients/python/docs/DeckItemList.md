# DeckItemList

Deck items (unpaginated).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[DeckItem]**](DeckItem.md) | Items. | 

## Example

```python
from kotobaya_client.models.deck_item_list import DeckItemList

# TODO update the JSON string below
json = "{}"
# create an instance of DeckItemList from a JSON string
deck_item_list_instance = DeckItemList.from_json(json)
# print the JSON string representation of the object
print(DeckItemList.to_json())

# convert the object into a dict
deck_item_list_dict = deck_item_list_instance.to_dict()
# create an instance of DeckItemList from a dict
deck_item_list_from_dict = DeckItemList.from_dict(deck_item_list_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


