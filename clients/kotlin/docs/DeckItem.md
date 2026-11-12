
# DeckItem

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **addedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | When the item was added. |  |
| **deckId** | **kotlin.String** | The deck. |  |
| **entry** | [**DictEntrySummary**](DictEntrySummary.md) | Dictionary summary; null for note items and entries missing from the dictionary. |  |
| **entrySeq** | **kotlin.Long** | Entry; exactly one of &#x60;entry_seq&#x60; and &#x60;note_id&#x60; is set. |  |
| **itemKey** | **kotlin.String** | &#x60;e:&lt;entry_seq&gt;&#x60; or &#x60;n:&lt;note id&gt;&#x60;. |  |
| **noteId** | **kotlin.String** | Note without a dictionary entry (merge losers resolved to the survivor). |  |
| **occurrenceCount** | **kotlin.Int** | Occurrences in the deck&#39;s media; 0 for non-media decks. |  |
| **position** | **kotlin.Long** | Ascending new-card order; for media decks the rank by in-deck frequency. |  |
| **status** | [**WordStatusValue**](WordStatusValue.md) | Effective word status of the entry; null for note items. |  |



