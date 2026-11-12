# DeckItemPutSchema

Adds an item to a user deck or moves it. Whole-row last-writer-wins on hlc together with deck.item_removed. OPS.md#deckitem_put

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**deck_id** | **str** | Deck; must exist and be of type user. | 
**item_key** | **str** | What is (or was) in the deck. entity_id &#x3D; &#39;&lt;deck_id&gt;|&lt;item_key&gt;&#39;. For &#39;n:&#39; keys the note must exist. | 
**position** | **int** | Ascending new-card order within the deck; sparse (clients space by 1024). | 

## Example

```python
from kotobaya_client.models.deck_item_put_schema import DeckItemPutSchema

# TODO update the JSON string below
json = "{}"
# create an instance of DeckItemPutSchema from a JSON string
deck_item_put_schema_instance = DeckItemPutSchema.from_json(json)
# print the JSON string representation of the object
print(DeckItemPutSchema.to_json())

# convert the object into a dict
deck_item_put_schema_dict = deck_item_put_schema_instance.to_dict()
# create an instance of DeckItemPutSchema from a dict
deck_item_put_schema_from_dict = DeckItemPutSchema.from_dict(deck_item_put_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


