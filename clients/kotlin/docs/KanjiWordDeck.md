
# KanjiWordDeck

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **deckId** | **kotlin.String** | Deck id (&#x60;app.deck.id&#x60;). |  |
| **name** | **kotlin.String** | Deck display name (&#x60;app.deck.name&#x60;, not unique). |  |
| **occurrenceCount** | **kotlin.Int** | &#x60;app.deck_item.occurrence_count&#x60; of this entry in this deck: occurrences in the deck&#39;s media; 0 for non-media decks. |  |
| **type** | [**DeckType**](DeckType.md) | Deck type. Never &#x60;smart&#x60; in practice (smart decks have no items); clients must still accept it. |  |



