
# DeckSettings

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **addKanji** | [**inline**](#AddKanji) | Whether adding a vocab card also adds cards for its unlearned kanji. |  [optional] |
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



