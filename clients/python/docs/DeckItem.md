# DeckItem

An item of a deck (`app.deck_item`) with dictionary summary and status.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**added_at** | **datetime** | When the item was added. | 
**deck_id** | **str** | The deck. | 
**entry** | [**DictEntrySummary**](DictEntrySummary.md) | Dictionary summary; null for note items and entries missing from the dictionary. | 
**entry_seq** | **int** | Entry; exactly one of &#x60;entry_seq&#x60; and &#x60;note_id&#x60; is set. | 
**item_key** | **str** | &#x60;e:&lt;entry_seq&gt;&#x60; or &#x60;n:&lt;note id&gt;&#x60;. | 
**note_id** | **str** | Note without a dictionary entry (merge losers resolved to the survivor). | 
**occurrence_count** | **int** | Occurrences in the deck&#39;s media; 0 for non-media decks. | 
**position** | **int** | Ascending new-card order; for media decks the rank by in-deck frequency. | 
**status** | [**WordStatusValue**](WordStatusValue.md) | Effective word status of the entry; null for note items. | 

## Example

```python
from kotobaya_client.models.deck_item import DeckItem

# TODO update the JSON string below
json = "{}"
# create an instance of DeckItem from a JSON string
deck_item_instance = DeckItem.from_json(json)
# print the JSON string representation of the object
print(DeckItem.to_json())

# convert the object into a dict
deck_item_dict = deck_item_instance.to_dict()
# create an instance of DeckItem from a dict
deck_item_from_dict = DeckItem.from_dict(deck_item_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


