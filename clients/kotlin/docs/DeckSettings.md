
# DeckSettings

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **addKanji** | [**inline**](#AddKanji) | **Deprecated (ADR-040): stored and returned, no longer read.** Was: whether adding a vocab card also adds cards for its unlearned kanji. Kanji cards now come before their first word for every deck and are created when the queue draws the word (&#x60;getReviewQueue&#x60; \&quot;Kanji first\&quot;); &#x60;off&#x60; no longer opts out. Still accepted by &#x60;createDeck&#x60; / &#x60;updateDeck&#x60; and synced, so old clients keep working. |  [optional] |
| **cardKinds** | **kotlin.collections.List&lt;kotlin.String&gt;** | Card kinds to create for notes added from this deck, overriding note-type defaults. |  [optional] |
| **order** | [**inline**](#Order) | New-card order inside the deck. Default &#x60;deck_frequency&#x60; for media decks, &#x60;position&#x60; otherwise. |  [optional] |


<a id="AddKanji"></a>
## Enum: add_kanji
| Name | Value |
| ---- | ----- |
| addKanji | off, before, alongside |


<a id="Order"></a>
## Enum: order
| Name | Value |
| ---- | ----- |
| order | deck_frequency, global_frequency, position, chronological |



