# DeckList

Decks.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[Deck]**](Deck.md) | Decks in new-card priority order. | 

## Example

```python
from kotobaya_client.models.deck_list import DeckList

# TODO update the JSON string below
json = "{}"
# create an instance of DeckList from a JSON string
deck_list_instance = DeckList.from_json(json)
# print the JSON string representation of the object
print(DeckList.to_json())

# convert the object into a dict
deck_list_dict = deck_list_instance.to_dict()
# create an instance of DeckList from a dict
deck_list_from_dict = DeckList.from_dict(deck_list_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


