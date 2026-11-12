# DeckItemPage

A page of deck items.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[DeckItem]**](DeckItem.md) | Items in the requested order. | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.deck_item_page import DeckItemPage

# TODO update the JSON string below
json = "{}"
# create an instance of DeckItemPage from a JSON string
deck_item_page_instance = DeckItemPage.from_json(json)
# print the JSON string representation of the object
print(DeckItemPage.to_json())

# convert the object into a dict
deck_item_page_dict = deck_item_page_instance.to_dict()
# create an instance of DeckItemPage from a dict
deck_item_page_from_dict = DeckItemPage.from_dict(deck_item_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


