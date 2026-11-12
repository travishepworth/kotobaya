# DeckItemRemovedSchema

Removes an item from a user deck (reversible tombstone). OPS.md#deckitem_removed

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**deck_id** | **str** | Deck; must exist and be of type user. | 
**item_key** | **str** | What is (or was) in the deck. entity_id &#x3D; &#39;&lt;deck_id&gt;|&lt;item_key&gt;&#39;. For &#39;n:&#39; keys the note must exist. | 

## Example

```python
from kotobaya_client.models.deck_item_removed_schema import DeckItemRemovedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of DeckItemRemovedSchema from a JSON string
deck_item_removed_schema_instance = DeckItemRemovedSchema.from_json(json)
# print the JSON string representation of the object
print(DeckItemRemovedSchema.to_json())

# convert the object into a dict
deck_item_removed_schema_dict = deck_item_removed_schema_instance.to_dict()
# create an instance of DeckItemRemovedSchema from a dict
deck_item_removed_schema_from_dict = DeckItemRemovedSchema.from_dict(deck_item_removed_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


