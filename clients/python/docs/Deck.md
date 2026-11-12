# Deck

A deck (`app.deck`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**content_blob_hash** | **str** | Snapshot blob of the items for &#x60;content_version&#x60;. | 
**content_version** | **int** | Version of the server-generated item set (media/list decks); 0 &#x3D; none yet or not applicable. | 
**created_at** | **datetime** | Creation time. | 
**deleted_at** | **datetime** | Tombstone. | 
**enabled** | **bool** | false &#x3D; supplies no new cards (coverage still shown). | 
**id** | **str** | Deck id; equals &#x60;media_id&#x60; for media decks. | 
**item_count** | **int** | Derived - number of live items (0 for smart decks). | 
**language** | **str** | BCP 47; always &#x60;ja&#x60; in v1. | 
**list_key** | **str** | Set iff &#x60;type&#x60; is &#x60;list&#x60;. | 
**media_id** | **str** | Set iff &#x60;type&#x60; is &#x60;media&#x60;. | 
**name** | **str** | Display name (not unique). | 
**new_cards_per_day** | **int** | Per-deck daily new-card limit; null &#x3D; only the global limit. | 
**priority** | **int** | New cards are drawn from enabled decks in ascending &#x60;(priority, id)&#x60;. | 
**query** | **str** | Set iff &#x60;type&#x60; is &#x60;smart&#x60; - the saved card search string. | 
**reviews_per_day** | **int** | Per-deck daily review limit; null &#x3D; only the global limit. | 
**settings** | [**DeckSettings**](DeckSettings.md) |  | 
**type** | [**DeckType**](DeckType.md) |  | 
**updated_at** | **datetime** | Last change. | 

## Example

```python
from kotobaya_client.models.deck import Deck

# TODO update the JSON string below
json = "{}"
# create an instance of Deck from a JSON string
deck_instance = Deck.from_json(json)
# print the JSON string representation of the object
print(Deck.to_json())

# convert the object into a dict
deck_dict = deck_instance.to_dict()
# create an instance of Deck from a dict
deck_from_dict = Deck.from_dict(deck_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


