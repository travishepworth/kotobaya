# DeckCreate

New deck. Which properties are allowed depends on `type` (see `POST /decks`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**enabled** | **bool** | Whether the deck supplies new cards. | [optional] [default to True]
**id** | **str** | Optional client-generated UUIDv7 (idempotent creation). | [optional] 
**list_key** | **str** | &#x60;list&#x60; only (required) - a key from &#x60;GET /deck-lists&#x60;. | [optional] 
**name** | **str** | Display name; required for &#x60;user&#x60; and &#x60;smart&#x60;. | [optional] 
**new_cards_per_day** | **int** | Per-deck new-card limit. | [optional] 
**priority** | **int** | New-card priority (lower first). | [optional] [default to 0]
**query** | **str** | &#x60;smart&#x60; only (required) - card search string. | [optional] 
**reviews_per_day** | **int** | Per-deck review limit. | [optional] 
**settings** | [**DeckSettings**](DeckSettings.md) |  | [optional] 
**type** | **str** | Deck type. Media decks cannot be created here. | 

## Example

```python
from kotobaya_client.models.deck_create import DeckCreate

# TODO update the JSON string below
json = "{}"
# create an instance of DeckCreate from a JSON string
deck_create_instance = DeckCreate.from_json(json)
# print the JSON string representation of the object
print(DeckCreate.to_json())

# convert the object into a dict
deck_create_dict = deck_create_instance.to_dict()
# create an instance of DeckCreate from a dict
deck_create_from_dict = DeckCreate.from_dict(deck_create_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


