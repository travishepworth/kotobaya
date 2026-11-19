
# Deck

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **contentBlobHash** | **kotlin.String** | Snapshot blob of the items for &#x60;content_version&#x60;. |  |
| **contentVersion** | **kotlin.Int** | Version of the server-generated item set (media/list decks); 0 &#x3D; none yet or not applicable. |  |
| **createdAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Creation time. |  |
| **deletedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Tombstone. |  |
| **enabled** | **kotlin.Boolean** | false &#x3D; supplies no new cards (coverage still shown). |  |
| **id** | **kotlin.String** | Deck id; equals &#x60;media_id&#x60; for media decks. |  |
| **itemCount** | **kotlin.Int** | Derived - number of live items (0 for smart decks). |  |
| **language** | **kotlin.String** | BCP 47; always &#x60;ja&#x60; in v1. |  |
| **listKey** | **kotlin.String** | Set iff &#x60;type&#x60; is &#x60;list&#x60;. |  |
| **mediaId** | **kotlin.String** | Set iff &#x60;type&#x60; is &#x60;media&#x60;. |  |
| **name** | **kotlin.String** | Display name (not unique). |  |
| **newCardsPerDay** | **kotlin.Int** | Per-deck daily new-card limit; null &#x3D; only the global limit. |  |
| **priority** | **kotlin.Int** | New cards are drawn from enabled decks in ascending &#x60;(priority, id)&#x60;. |  |
| **query** | **kotlin.String** | Set iff &#x60;type&#x60; is &#x60;smart&#x60; - the saved card search string. |  |
| **reviewsPerDay** | **kotlin.Int** | Per-deck daily review limit; null &#x3D; only the global limit. |  |
| **settings** | [**DeckSettings**](DeckSettings.md) |  |  |
| **type** | [**DeckType**](DeckType.md) |  |  |
| **updatedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Last change. |  |
| **progress** | [**DeckProgress**](DeckProgress.md) | ADR-041 (additive). \&quot;N of M words learned (P%)\&quot;, computed at request time by the shared core &#x60;DeckProgress.count&#x60; (contract: &#x60;core/.../contracts/decks/DeckProgress.kt&#x60;) so the server and the phone&#39;s local API agree. Null for smart decks (no items) and for a tombstoned deck (&#x60;deleted_at&#x60; non-null). Sent on every &#x60;Deck&#x60; (list, get, create, update) by servers implementing ADR-041; absent (older server): clients show no progress line. Not learning data: nothing is stored and no op is emitted.  |  [optional] |



