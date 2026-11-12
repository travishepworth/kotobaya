# DeckItemPut

Optional placement of an item.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**position** | **int** | Position to store. Absent - append (new item) or keep (existing live item). | [optional] 

## Example

```python
from kotobaya_client.models.deck_item_put import DeckItemPut

# TODO update the JSON string below
json = "{}"
# create an instance of DeckItemPut from a JSON string
deck_item_put_instance = DeckItemPut.from_json(json)
# print the JSON string representation of the object
print(DeckItemPut.to_json())

# convert the object into a dict
deck_item_put_dict = deck_item_put_instance.to_dict()
# create an instance of DeckItemPut from a dict
deck_item_put_from_dict = DeckItemPut.from_dict(deck_item_put_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


