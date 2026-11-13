# KanjiWordDeck

One of your live decks holding the entry as a live item (C-17).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**deck_id** | **str** | Deck id (&#x60;app.deck.id&#x60;). | 
**name** | **str** | Deck display name (&#x60;app.deck.name&#x60;, not unique). | 
**occurrence_count** | **int** | &#x60;app.deck_item.occurrence_count&#x60; of this entry in this deck: occurrences in the deck&#39;s media; 0 for non-media decks. | 
**type** | [**DeckType**](DeckType.md) | Deck type. Never &#x60;smart&#x60; in practice (smart decks have no items); clients must still accept it. | 

## Example

```python
from kotobaya_client.models.kanji_word_deck import KanjiWordDeck

# TODO update the JSON string below
json = "{}"
# create an instance of KanjiWordDeck from a JSON string
kanji_word_deck_instance = KanjiWordDeck.from_json(json)
# print the JSON string representation of the object
print(KanjiWordDeck.to_json())

# convert the object into a dict
kanji_word_deck_dict = kanji_word_deck_instance.to_dict()
# create an instance of KanjiWordDeck from a dict
kanji_word_deck_from_dict = KanjiWordDeck.from_dict(kanji_word_deck_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


