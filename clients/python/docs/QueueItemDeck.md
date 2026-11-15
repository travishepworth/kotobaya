# QueueItemDeck

C-25. A deck holding the note of a queue item (`QueueItem.decks`); a subset of `Deck` with identical values. Fields added later will be optional, so clients must ignore unknown members.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Deck id (&#x60;app.deck.id&#x60;); usable with &#x60;getDeck&#x60;. | 
**name** | **str** | Deck display name (&#x60;app.deck.name&#x60;, not unique). | 
**priority** | **int** | &#x60;app.deck.priority&#x60;: new cards are drawn from enabled decks in ascending &#x60;(priority, id)&#x60;. | 

## Example

```python
from kotobaya_client.models.queue_item_deck import QueueItemDeck

# TODO update the JSON string below
json = "{}"
# create an instance of QueueItemDeck from a JSON string
queue_item_deck_instance = QueueItemDeck.from_json(json)
# print the JSON string representation of the object
print(QueueItemDeck.to_json())

# convert the object into a dict
queue_item_deck_dict = queue_item_deck_instance.to_dict()
# create an instance of QueueItemDeck from a dict
queue_item_deck_from_dict = QueueItemDeck.from_dict(queue_item_deck_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


