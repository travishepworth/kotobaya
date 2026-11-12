
# Deckcreatedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **id** | **kotlin.String** | Id of the new deck (for a media deck: equal to media_id). Equals the envelope&#39;s entity_id. |  |
| **name** | **kotlin.String** | Display name; not unique. |  |
| **type** | [**inline**](#Type) | Immutable. media and list: server-emitted only. |  |
| **enabled** | **kotlin.Boolean** | false &#x3D; supplies no new cards. Default true. |  [optional] |
| **language** | [**inline**](#Language) | Default &#39;ja&#39;. |  [optional] |
| **listKey** | **kotlin.String** | Required iff type &#x3D; list. Slug, e.g. jlpt-n3. |  [optional] |
| **mediaId** | **kotlin.String** | Required iff type &#x3D; media; equals id. |  [optional] |
| **newCardsPerDay** | **kotlin.Int** | Per-deck daily new-card limit; null &#x3D; only the global limit applies. |  [optional] |
| **priority** | **kotlin.Int** | New cards are drawn from enabled decks in ascending (priority, id). Default 0. |  [optional] |
| **query** | **kotlin.String** | Card search-language string. Required iff type &#x3D; smart. |  [optional] |
| **reviewsPerDay** | **kotlin.Int** | Per-deck daily new-card limit; null &#x3D; only the global limit applies. |  [optional] |
| **settings** | [**kotlin.Any**](.md) | Open object; each top-level key is its own register &#39;settings.&lt;key&gt;&#39;. Known keys: order, add_kanji, card_kinds. Keys whose value is null, &#39;&#39;, [] or {} are dropped at creation. |  [optional] |


<a id="Type"></a>
## Enum: type
| Name | Value |
| ---- | ----- |
| type | user, media, list, smart |


<a id="Language"></a>
## Enum: language
| Name | Value |
| ---- | ----- |
| language | ja |



