# DecksApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**addDeckItems**](DecksApi.md#addDeckItems) | **POST** /decks/{deck_id}/items | Add several items to a user deck. |
| [**createDeck**](DecksApi.md#createDeck) | **POST** /decks | Create a user or smart deck, or enable a generated list. |
| [**deleteDeck**](DecksApi.md#deleteDeck) | **DELETE** /decks/{deck_id} | Delete a user, smart or list deck. Notes and cards are untouched. |
| [**getDeck**](DecksApi.md#getDeck) | **GET** /decks/{deck_id} | One deck. |
| [**getDeckCoverage**](DecksApi.md#getDeckCoverage) | **GET** /decks/{deck_id}/coverage | Coverage of a deck&#39;s items by your word statuses. |
| [**listDeckItems**](DecksApi.md#listDeckItems) | **GET** /decks/{deck_id}/items | Items of a deck with dictionary summary and word status. |
| [**listDeckLists**](DecksApi.md#listDeckLists) | **GET** /deck-lists | Generated lists (JLPT, frequency bands) that can be enabled as &#x60;list&#x60; decks. |
| [**listDecks**](DecksApi.md#listDecks) | **GET** /decks | List decks. |
| [**putDeckItem**](DecksApi.md#putDeckItem) | **PUT** /decks/{deck_id}/items/{item_key} | Add one item to a user deck, or move it. |
| [**removeDeckItem**](DecksApi.md#removeDeckItem) | **DELETE** /decks/{deck_id}/items/{item_key} | Remove one item from a user deck (reversible by putting it again). |
| [**updateDeck**](DecksApi.md#updateDeck) | **PATCH** /decks/{deck_id} | Rename, reprioritise, enable/disable, set limits or settings. |


<a id="addDeckItems"></a>
# **addDeckItems**
> DeckItemList addDeckItems(deckId, deckItemsAdd)

Add several items to a user deck.

Server emits one &#x60;deck.item_put&#x60; per element, in array order, in one transaction. Each element names exactly one of &#x60;entry_seq&#x60; or &#x60;note_id&#x60;. A &#x60;note_id&#x60; whose note has an &#x60;entry_seq&#x60; is stored in entry form (&#x60;e:&lt;seq&gt;&#x60;); a merge loser&#39;s id is resolved to the survivor first. Elements without &#x60;position&#x60; are appended after the current maximum, 1024 apart, in array order. An element already present (live or removed) is put again (the put clears a removal and moves it). Duplicates inside the array: the last wins. 1 to 1000 elements. Not a &#x60;user&#x60; deck: &#x60;409 deck_read_only&#x60;. Unknown entry or note: &#x60;422&#x60; naming the element; nothing is applied. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DecksApi()
val deckId : kotlin.String = 01931f40-2000-7000-8000-000000000d01 // kotlin.String | Deck id. For a media deck this equals the media id.
val deckItemsAdd : DeckItemsAdd = {"items":[{"entry_seq":1358280},{"entry_seq":1002430,"position":2048},{"note_id":"01931f40-1a2b-7c3d-8e4f-000000000a02"}]} // DeckItemsAdd | 
try {
    val result : DeckItemList = apiInstance.addDeckItems(deckId, deckItemsAdd)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DecksApi#addDeckItems")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DecksApi#addDeckItems")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **deckId** | **kotlin.String**| Deck id. For a media deck this equals the media id. | |
| **deckItemsAdd** | [**DeckItemsAdd**](DeckItemsAdd.md)|  | |

### Return type

[**DeckItemList**](DeckItemList.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

<a id="createDeck"></a>
# **createDeck**
> Deck createDeck(deckCreate)

Create a user or smart deck, or enable a generated list.

Server emits &#x60;deck.created&#x60; (origin: server device, which is what makes &#x60;list&#x60; decks legal here).  - &#x60;type: user&#x60; - &#x60;name&#x60; required. Starts empty. - &#x60;type: smart&#x60; - &#x60;name&#x60; and &#x60;query&#x60; required; &#x60;query&#x60; must parse (&#x60;422 invalid_query&#x60;). - &#x60;type: list&#x60; - &#x60;list_key&#x60; required and must be in &#x60;GET /deck-lists&#x60; (&#x60;422&#x60; otherwise);   &#x60;name&#x60; defaults to the catalogue title. Under the op-apply lock: if a live deck with   that &#x60;list_key&#x60; exists it is returned with &#x60;200&#x60; and nothing is emitted (a sent &#x60;id&#x60;   is then ignored). Otherwise the deck is created and a job of kind &#x60;deck-build&#x60; is   queued (payload &#x60;{\&quot;deck_id\&quot;}&#x60;, dedupe key &#x60;deck-build:&lt;deck id&gt;&#x60;) that builds the item   snapshot and makes the server emit &#x60;deck.content_published&#x60;; until then the deck has   &#x60;content_version &#x3D; 0&#x60; and no items (&#x60;contracts/db/VOCAB_LISTS.md&#x60; 4.4). - &#x60;type: media&#x60; is &#x60;422&#x60;: media decks are created only by import jobs.  Properties that do not belong to the type (&#x60;query&#x60; on a user deck, ...) are &#x60;422&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DecksApi()
val deckCreate : DeckCreate = {"list_key":"jlpt-n3","new_cards_per_day":5,"type":"list"} // DeckCreate | 
try {
    val result : Deck = apiInstance.createDeck(deckCreate)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DecksApi#createDeck")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DecksApi#createDeck")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **deckCreate** | [**DeckCreate**](DeckCreate.md)|  | |

### Return type

[**Deck**](Deck.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

<a id="deleteDeck"></a>
# **deleteDeck**
> deleteDeck(deckId)

Delete a user, smart or list deck. Notes and cards are untouched.

Server emits &#x60;deck.deleted&#x60;. A &#x60;media&#x60; deck is &#x60;409 deck_read_only&#x60;: delete the media (&#x60;DELETE /media/{media_id}&#x60;), which tombstones its deck. A deleted &#x60;list&#x60; deck can be enabled again later (a new deck with a new id). Already deleted: &#x60;204&#x60;, no op. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DecksApi()
val deckId : kotlin.String = 01931f40-2000-7000-8000-000000000d01 // kotlin.String | Deck id. For a media deck this equals the media id.
try {
    apiInstance.deleteDeck(deckId)
} catch (e: ClientException) {
    println("4xx response calling DecksApi#deleteDeck")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DecksApi#deleteDeck")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **deckId** | **kotlin.String**| Deck id. For a media deck this equals the media id. | |

### Return type

null (empty response body)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/problem+json

<a id="getDeck"></a>
# **getDeck**
> Deck getDeck(deckId, includeDeleted)

One deck.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DecksApi()
val deckId : kotlin.String = 01931f40-2000-7000-8000-000000000d01 // kotlin.String | Deck id. For a media deck this equals the media id.
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
try {
    val result : Deck = apiInstance.getDeck(deckId, includeDeleted)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DecksApi#getDeck")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DecksApi#getDeck")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **deckId** | **kotlin.String**| Deck id. For a media deck this equals the media id. | |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |

### Return type

[**Deck**](Deck.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="getDeckCoverage"></a>
# **getDeckCoverage**
> Coverage getDeckCoverage(deckId, hide)

Coverage of a deck&#39;s items by your word statuses.

Computed at request time from live &#x60;deck_item&#x60; rows that have an &#x60;entry_seq&#x60; and &#x60;word_status&#x60;. For &#x60;media&#x60; decks the result equals &#x60;GET /media/{id}/coverage&#x60; without &#x60;segment_id&#x60; (including &#x60;segments&#x60;, &#x60;curve&#x60; and token numbers). For &#x60;user&#x60; and &#x60;list&#x60; decks token numbers count each item once (&#x60;occurrence_count&#x60; is 0, treated as 1), &#x60;segments&#x60; is empty and &#x60;difficulty&#x60; is null. Smart decks: &#x60;409 not_applicable&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DecksApi()
val deckId : kotlin.String = 01931f40-2000-7000-8000-000000000d01 // kotlin.String | Deck id. For a media deck this equals the media id.
val hide : kotlin.collections.Set<kotlin.String> = [names, particles, ignored] // kotlin.collections.Set<kotlin.String> | Classes left out of every coverage number, comma-separated. `names`, `particles`, `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`; `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide nothing. 
try {
    val result : Coverage = apiInstance.getDeckCoverage(deckId, hide)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DecksApi#getDeckCoverage")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DecksApi#getDeckCoverage")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **deckId** | **kotlin.String**| Deck id. For a media deck this equals the media id. | |
| **hide** | [**kotlin.collections.Set&lt;kotlin.String&gt;**](kotlin.String.md)| Classes left out of every coverage number, comma-separated. &#x60;names&#x60;, &#x60;particles&#x60;, &#x60;auxiliary&#x60; &#x3D; entries whose &#x60;occurrence.pos_class&#x60; is &#x60;name&#x60;, &#x60;particle&#x60;, &#x60;auxiliary&#x60;; &#x60;ignored&#x60; &#x3D; entries whose word status is &#x60;blacklisted&#x60; or &#x60;ignored&#x60;; &#x60;unmapped&#x60; &#x3D; tokens that matched no dictionary entry. Default &#x60;ignored&#x60;. Pass an empty value (&#x60;hide&#x3D;&#x60;) to hide nothing.  | [optional] [enum: names, particles, auxiliary, ignored, unmapped] |

### Return type

[**Coverage**](Coverage.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="listDeckItems"></a>
# **listDeckItems**
> DeckItemPage listDeckItems(deckId, status, sort, limit, cursor)

Items of a deck with dictionary summary and word status.

Live items only. For a media deck this is the media&#39;s vocabulary list: &#x60;status&#x3D;new&#x60; with &#x60;sort&#x3D;occurrence_count&#x60; gives \&quot;unknown words by in-deck frequency, global rank beside them\&quot;. An item whose &#x60;note_id&#x60; is a merge loser is reported with the survivor&#39;s id. Items whose entry is missing from the dictionary have &#x60;entry: null&#x60;. Smart decks have no items: &#x60;409 not_applicable&#x60; (use &#x60;GET /cards?query&#x3D;&#x60;). Deleted deck: &#x60;410 gone&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DecksApi()
val deckId : kotlin.String = 01931f40-2000-7000-8000-000000000d01 // kotlin.String | Deck id. For a media deck this equals the media id.
val status : kotlin.collections.Set<WordStatusValue> = ["new","learning"] // kotlin.collections.Set<WordStatusValue> | Only items whose effective word status is one of these (comma-separated). Items without an `entry_seq` have no status and match only when this parameter is absent.
val sort : kotlin.String = occurrence_count // kotlin.String | `position` = the deck's new-card order (ties by `item_key`); `occurrence_count` = most frequent in this deck first; `freq_rank` = globally most frequent first (NULLs last). Ties by `item_key`.
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : DeckItemPage = apiInstance.listDeckItems(deckId, status, sort, limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DecksApi#listDeckItems")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DecksApi#listDeckItems")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **deckId** | **kotlin.String**| Deck id. For a media deck this equals the media id. | |
| **status** | [**kotlin.collections.Set&lt;WordStatusValue&gt;**](WordStatusValue.md)| Only items whose effective word status is one of these (comma-separated). Items without an &#x60;entry_seq&#x60; have no status and match only when this parameter is absent. | [optional] |
| **sort** | **kotlin.String**| &#x60;position&#x60; &#x3D; the deck&#39;s new-card order (ties by &#x60;item_key&#x60;); &#x60;occurrence_count&#x60; &#x3D; most frequent in this deck first; &#x60;freq_rank&#x60; &#x3D; globally most frequent first (NULLs last). Ties by &#x60;item_key&#x60;. | [optional] [enum: position, occurrence_count, freq_rank] |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**DeckItemPage**](DeckItemPage.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="listDeckLists"></a>
# **listDeckLists**
> DeckListCatalogue listDeckLists()

Generated lists (JLPT, frequency bands) that can be enabled as &#x60;list&#x60; decks.

The catalogue is computed by the server from the current dictionary release: every loaded vocabulary list (&#x60;ref.vocab_list&#x60;: &#x60;jlpt-n5&#x60; ... &#x60;jlpt-n1&#x60; once the JLPT source is loaded, C-21) and, per loaded frequency list, &#x60;freq-&lt;list id&gt;-&lt;N&gt;&#x60; for N in 1000, 2000, 5000, 10000, 20000 (&#x60;_&#x60; in the list id becomes &#x60;-&#x60;). A list whose &#x60;item_count&#x60; would be 0 is not offered. &#x60;item_count&#x60; equals the number of items a &#x60;deck-build&#x60; of that key publishes against the same release (live entries only). Normative rules: &#x60;contracts/db/VOCAB_LISTS.md&#x60; 4.1. &#x60;deck_id&#x60; is set when a live deck for the key exists. Not paginated; ordered by &#x60;list_key&#x60;. Empty when no dictionary release is loaded. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DecksApi()
try {
    val result : DeckListCatalogue = apiInstance.listDeckLists()
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DecksApi#listDeckLists")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DecksApi#listDeckLists")
    e.printStackTrace()
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

[**DeckListCatalogue**](DeckListCatalogue.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="listDecks"></a>
# **listDecks**
> DeckList listDecks(type, includeDeleted)

List decks.

Not paginated. Ordered by &#x60;(priority, id)&#x60; ascending - the order new cards are drawn in.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DecksApi()
val type : DeckType = media // DeckType | Only decks of this type.
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
try {
    val result : DeckList = apiInstance.listDecks(type, includeDeleted)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DecksApi#listDecks")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DecksApi#listDecks")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **type** | [**DeckType**](.md)| Only decks of this type. | [optional] [enum: user, media, list, smart] |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |

### Return type

[**DeckList**](DeckList.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="putDeckItem"></a>
# **putDeckItem**
> DeckItem putDeckItem(deckId, itemKey, deckItemPut)

Add one item to a user deck, or move it.

Server emits &#x60;deck.item_put&#x60;. Without a body, or without &#x60;position&#x60;: a new item is appended (maximum position + 1024); an existing live item keeps its position. An &#x60;n:&lt;id&gt;&#x60; key whose note has an &#x60;entry_seq&#x60; is &#x60;422&#x60; (use the &#x60;e:&#x60; form); the note or entry must exist. Not a &#x60;user&#x60; deck: &#x60;409 deck_read_only&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DecksApi()
val deckId : kotlin.String = 01931f40-2000-7000-8000-000000000d01 // kotlin.String | Deck id. For a media deck this equals the media id.
val itemKey : kotlin.String = e:1358280 // kotlin.String | `e:<entry_seq>` or `n:<note uuid, lowercase>`. The colon need not be percent-encoded.
val deckItemPut : DeckItemPut = {} // DeckItemPut | 
try {
    val result : DeckItem = apiInstance.putDeckItem(deckId, itemKey, deckItemPut)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DecksApi#putDeckItem")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DecksApi#putDeckItem")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **deckId** | **kotlin.String**| Deck id. For a media deck this equals the media id. | |
| **itemKey** | **kotlin.String**| &#x60;e:&lt;entry_seq&gt;&#x60; or &#x60;n:&lt;note uuid, lowercase&gt;&#x60;. The colon need not be percent-encoded. | |
| **deckItemPut** | [**DeckItemPut**](DeckItemPut.md)|  | [optional] |

### Return type

[**DeckItem**](DeckItem.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

<a id="removeDeckItem"></a>
# **removeDeckItem**
> removeDeckItem(deckId, itemKey)

Remove one item from a user deck (reversible by putting it again).

Server emits &#x60;deck.item_removed&#x60;. Item absent or already removed: &#x60;204&#x60;, no op. Not a &#x60;user&#x60; deck: &#x60;409 deck_read_only&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DecksApi()
val deckId : kotlin.String = 01931f40-2000-7000-8000-000000000d01 // kotlin.String | Deck id. For a media deck this equals the media id.
val itemKey : kotlin.String = e:1358280 // kotlin.String | `e:<entry_seq>` or `n:<note uuid, lowercase>`. The colon need not be percent-encoded.
try {
    apiInstance.removeDeckItem(deckId, itemKey)
} catch (e: ClientException) {
    println("4xx response calling DecksApi#removeDeckItem")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DecksApi#removeDeckItem")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **deckId** | **kotlin.String**| Deck id. For a media deck this equals the media id. | |
| **itemKey** | **kotlin.String**| &#x60;e:&lt;entry_seq&gt;&#x60; or &#x60;n:&lt;note uuid, lowercase&gt;&#x60;. The colon need not be percent-encoded. | |

### Return type

null (empty response body)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/problem+json

<a id="updateDeck"></a>
# **updateDeck**
> Deck updateDeck(deckId, deckPatch)

Rename, reprioritise, enable/disable, set limits or settings.

Server emits one &#x60;deck.updated&#x60; with the registers present: &#x60;name&#x60;, &#x60;priority&#x60;, &#x60;enabled&#x60;, &#x60;new_cards_per_day&#x60;, &#x60;reviews_per_day&#x60;, &#x60;query&#x60; (smart decks only, must parse), and one &#x60;settings.&lt;key&gt;&#x60; per key of &#x60;settings&#x60; (partial map; a &#x60;null&#x60; value removes the key). Works for every deck type. &#x60;type&#x60;, &#x60;media_id&#x60;, &#x60;list_key&#x60; are immutable (&#x60;422&#x60;). An empty body is &#x60;422&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DecksApi()
val deckId : kotlin.String = 01931f40-2000-7000-8000-000000000d01 // kotlin.String | Deck id. For a media deck this equals the media id.
val deckPatch : DeckPatch = {"enabled":false} // DeckPatch | 
try {
    val result : Deck = apiInstance.updateDeck(deckId, deckPatch)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DecksApi#updateDeck")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DecksApi#updateDeck")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **deckId** | **kotlin.String**| Deck id. For a media deck this equals the media id. | |
| **deckPatch** | [**DeckPatch**](DeckPatch.md)|  | |

### Return type

[**Deck**](Deck.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

