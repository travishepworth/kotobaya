
# DeckCreate

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **type** | [**inline**](#Type) | Deck type. Media decks cannot be created here. |  |
| **enabled** | **kotlin.Boolean** | Whether the deck supplies new cards. |  [optional] |
| **id** | **kotlin.String** | Optional client-generated UUIDv7 (idempotent creation). |  [optional] |
| **listKey** | **kotlin.String** | &#x60;list&#x60; only (required) - a key from &#x60;GET /deck-lists&#x60;. |  [optional] |
| **name** | **kotlin.String** | Display name; required for &#x60;user&#x60; and &#x60;smart&#x60;. |  [optional] |
| **newCardsPerDay** | **kotlin.Int** | Per-deck new-card limit. |  [optional] |
| **priority** | **kotlin.Int** | New-card priority (lower first). |  [optional] |
| **query** | **kotlin.String** | &#x60;smart&#x60; only (required) - card search string. |  [optional] |
| **reviewsPerDay** | **kotlin.Int** | Per-deck review limit. |  [optional] |
| **settings** | [**DeckSettings**](DeckSettings.md) |  |  [optional] |


<a id="Type"></a>
## Enum: type
| Name | Value |
| ---- | ----- |
| type | user, smart, list |



