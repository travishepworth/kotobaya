
# DeckUpdatedSchemaSet

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **enabled** | **kotlin.Boolean** | Register &#39;enabled&#39;. |  [optional] |
| **name** | **kotlin.String** | Register &#39;name&#39;. |  [optional] |
| **newCardsPerDay** | **kotlin.Int** | Per-deck daily new-card limit; null &#x3D; only the global limit applies. |  [optional] |
| **priority** | **kotlin.Int** | Register &#39;priority&#39;. |  [optional] |
| **query** | **kotlin.String** | Register &#39;query&#39;. Only valid for smart decks (server rejects otherwise). |  [optional] |
| **reviewsPerDay** | **kotlin.Int** | Per-deck daily review limit; null &#x3D; only the global limit applies. |  [optional] |
| **settings** | [**kotlin.Any**](.md) | Open object; each top-level key is its own register &#39;settings.&lt;key&gt;&#39;. Known keys: order, add_kanji, card_kinds. A null value removes the key (the register keeps its clock). |  [optional] |



