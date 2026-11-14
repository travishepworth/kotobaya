# DecksApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**addDeckItems**](DecksApi.md#adddeckitems) | **POST** /decks/{deck_id}/items | Add several items to a user deck. |
| [**createDeck**](DecksApi.md#createdeck) | **POST** /decks | Create a user or smart deck, or enable a generated list. |
| [**deleteDeck**](DecksApi.md#deletedeck) | **DELETE** /decks/{deck_id} | Delete a user, smart or list deck. Notes and cards are untouched. |
| [**getDeck**](DecksApi.md#getdeck) | **GET** /decks/{deck_id} | One deck. |
| [**getDeckCoverage**](DecksApi.md#getdeckcoverage) | **GET** /decks/{deck_id}/coverage | Coverage of a deck\&#39;s items by your word statuses. |
| [**listDeckItems**](DecksApi.md#listdeckitems) | **GET** /decks/{deck_id}/items | Items of a deck with dictionary summary and word status. |
| [**listDeckLists**](DecksApi.md#listdecklists) | **GET** /deck-lists | Generated lists (JLPT, frequency bands) that can be enabled as &#x60;list&#x60; decks. |
| [**listDecks**](DecksApi.md#listdecks) | **GET** /decks | List decks. |
| [**putDeckItem**](DecksApi.md#putdeckitem) | **PUT** /decks/{deck_id}/items/{item_key} | Add one item to a user deck, or move it. |
| [**removeDeckItem**](DecksApi.md#removedeckitem) | **DELETE** /decks/{deck_id}/items/{item_key} | Remove one item from a user deck (reversible by putting it again). |
| [**updateDeck**](DecksApi.md#updatedeck) | **PATCH** /decks/{deck_id} | Rename, reprioritise, enable/disable, set limits or settings. |



## addDeckItems

> DeckItemList addDeckItems(deckId, deckItemsAdd)

Add several items to a user deck.

Server emits one &#x60;deck.item_put&#x60; per element, in array order, in one transaction. Each element names exactly one of &#x60;entry_seq&#x60; or &#x60;note_id&#x60;. A &#x60;note_id&#x60; whose note has an &#x60;entry_seq&#x60; is stored in entry form (&#x60;e:&lt;seq&gt;&#x60;); a merge loser\&#39;s id is resolved to the survivor first. Elements without &#x60;position&#x60; are appended after the current maximum, 1024 apart, in array order. An element already present (live or removed) is put again (the put clears a removal and moves it). Duplicates inside the array: the last wins. 1 to 1000 elements. Not a &#x60;user&#x60; deck: &#x60;409 deck_read_only&#x60;. Unknown entry or note: &#x60;422&#x60; naming the element; nothing is applied. 

### Example

```ts
import {
  Configuration,
  DecksApi,
} from '@kotobaya/api-client';
import type { AddDeckItemsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DecksApi(config);

  const body = {
    // string | Deck id. For a media deck this equals the media id.
    deckId: 01931f40-2000-7000-8000-000000000d01,
    // DeckItemsAdd
    deckItemsAdd: {"items":[{"entry_seq":1358280},{"entry_seq":1002430,"position":2048},{"note_id":"01931f40-1a2b-7c3d-8e4f-000000000a02"}]},
  } satisfies AddDeckItemsRequest;

  try {
    const data = await api.addDeckItems(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deckId** | `string` | Deck id. For a media deck this equals the media id. | [Defaults to `undefined`] |
| **deckItemsAdd** | [DeckItemsAdd](DeckItemsAdd.md) |  | |

### Return type

[**DeckItemList**](DeckItemList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The items as stored, in request order (after de-duplication). |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## createDeck

> Deck createDeck(deckCreate)

Create a user or smart deck, or enable a generated list.

Server emits &#x60;deck.created&#x60; (origin: server device, which is what makes &#x60;list&#x60; decks legal here).  - &#x60;type: user&#x60; - &#x60;name&#x60; required. Starts empty. - &#x60;type: smart&#x60; - &#x60;name&#x60; and &#x60;query&#x60; required; &#x60;query&#x60; must parse (&#x60;422 invalid_query&#x60;). - &#x60;type: list&#x60; - &#x60;list_key&#x60; required and must be in &#x60;GET /deck-lists&#x60; (&#x60;422&#x60; otherwise);   &#x60;name&#x60; defaults to the catalogue title. Under the op-apply lock: if a live deck with   that &#x60;list_key&#x60; exists it is returned with &#x60;200&#x60; and nothing is emitted (a sent &#x60;id&#x60;   is then ignored). Otherwise the deck is created and a job of kind &#x60;deck-build&#x60; is   queued (payload &#x60;{\&quot;deck_id\&quot;}&#x60;, dedupe key &#x60;deck-build:&lt;deck id&gt;&#x60;) that builds the item   snapshot and makes the server emit &#x60;deck.content_published&#x60;; until then the deck has   &#x60;content_version &#x3D; 0&#x60; and no items (&#x60;contracts/db/VOCAB_LISTS.md&#x60; 4.4). - &#x60;type: media&#x60; is &#x60;422&#x60;: media decks are created only by import jobs.  Properties that do not belong to the type (&#x60;query&#x60; on a user deck, ...) are &#x60;422&#x60;. 

### Example

```ts
import {
  Configuration,
  DecksApi,
} from '@kotobaya/api-client';
import type { CreateDeckRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DecksApi(config);

  const body = {
    // DeckCreate
    deckCreate: {"list_key":"jlpt-n3","new_cards_per_day":5,"type":"list"},
  } satisfies CreateDeckRequest;

  try {
    const data = await api.createDeck(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deckCreate** | [DeckCreate](DeckCreate.md) |  | |

### Return type

[**Deck**](Deck.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The deck already existed (same &#x60;id&#x60;, or same &#x60;list_key&#x60;). |  -  |
| **201** | Created. |  * Kotobaya-Seq -  <br>  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## deleteDeck

> deleteDeck(deckId)

Delete a user, smart or list deck. Notes and cards are untouched.

Server emits &#x60;deck.deleted&#x60;. A &#x60;media&#x60; deck is &#x60;409 deck_read_only&#x60;: delete the media (&#x60;DELETE /media/{media_id}&#x60;), which tombstones its deck. A deleted &#x60;list&#x60; deck can be enabled again later (a new deck with a new id). Already deleted: &#x60;204&#x60;, no op. 

### Example

```ts
import {
  Configuration,
  DecksApi,
} from '@kotobaya/api-client';
import type { DeleteDeckRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DecksApi(config);

  const body = {
    // string | Deck id. For a media deck this equals the media id.
    deckId: 01931f40-2000-7000-8000-000000000d01,
  } satisfies DeleteDeckRequest;

  try {
    const data = await api.deleteDeck(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deckId** | `string` | Deck id. For a media deck this equals the media id. | [Defaults to `undefined`] |

### Return type

`void` (Empty response body)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Deleted. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getDeck

> Deck getDeck(deckId, includeDeleted)

One deck.

### Example

```ts
import {
  Configuration,
  DecksApi,
} from '@kotobaya/api-client';
import type { GetDeckRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DecksApi(config);

  const body = {
    // string | Deck id. For a media deck this equals the media id.
    deckId: 01931f40-2000-7000-8000-000000000d01,
    // boolean | Also return tombstoned rows (`deleted_at` set). (optional)
    includeDeleted: false,
  } satisfies GetDeckRequest;

  try {
    const data = await api.getDeck(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deckId** | `string` | Deck id. For a media deck this equals the media id. | [Defaults to `undefined`] |
| **includeDeleted** | `boolean` | Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [Optional] [Defaults to `false`] |

### Return type

[**Deck**](Deck.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The deck. |  -  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getDeckCoverage

> Coverage getDeckCoverage(deckId, hide)

Coverage of a deck\&#39;s items by your word statuses.

Computed at request time from live &#x60;deck_item&#x60; rows that have an &#x60;entry_seq&#x60; and &#x60;word_status&#x60;. For &#x60;media&#x60; decks the result equals &#x60;GET /media/{id}/coverage&#x60; without &#x60;segment_id&#x60; (including &#x60;segments&#x60;, &#x60;curve&#x60; and token numbers). For &#x60;user&#x60; and &#x60;list&#x60; decks token numbers count each item once (&#x60;occurrence_count&#x60; is 0, treated as 1), &#x60;segments&#x60; is empty and &#x60;difficulty&#x60; is null. Smart decks: &#x60;409 not_applicable&#x60;. 

### Example

```ts
import {
  Configuration,
  DecksApi,
} from '@kotobaya/api-client';
import type { GetDeckCoverageRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DecksApi(config);

  const body = {
    // string | Deck id. For a media deck this equals the media id.
    deckId: 01931f40-2000-7000-8000-000000000d01,
    // Set<'names' | 'particles' | 'auxiliary' | 'ignored' | 'unmapped'> | Classes left out of every coverage number, comma-separated. `names`, `particles`, `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`; `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide nothing.  (optional)
    hide: [names, particles, ignored],
  } satisfies GetDeckCoverageRequest;

  try {
    const data = await api.getDeckCoverage(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deckId** | `string` | Deck id. For a media deck this equals the media id. | [Defaults to `undefined`] |
| **hide** | `names`, `particles`, `auxiliary`, `ignored`, `unmapped` | Classes left out of every coverage number, comma-separated. &#x60;names&#x60;, &#x60;particles&#x60;, &#x60;auxiliary&#x60; &#x3D; entries whose &#x60;occurrence.pos_class&#x60; is &#x60;name&#x60;, &#x60;particle&#x60;, &#x60;auxiliary&#x60;; &#x60;ignored&#x60; &#x3D; entries whose word status is &#x60;blacklisted&#x60; or &#x60;ignored&#x60;; &#x60;unmapped&#x60; &#x3D; tokens that matched no dictionary entry. Default &#x60;ignored&#x60;. Pass an empty value (&#x60;hide&#x3D;&#x60;) to hide nothing.  | [Optional] [Enum: names, particles, auxiliary, ignored, unmapped] |

### Return type

[**Coverage**](Coverage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Coverage numbers. |  -  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listDeckItems

> DeckItemPage listDeckItems(deckId, status, sort, limit, cursor)

Items of a deck with dictionary summary and word status.

Live items only. For a media deck this is the media\&#39;s vocabulary list: &#x60;status&#x3D;new&#x60; with &#x60;sort&#x3D;occurrence_count&#x60; gives \&quot;unknown words by in-deck frequency, global rank beside them\&quot;. An item whose &#x60;note_id&#x60; is a merge loser is reported with the survivor\&#39;s id. Items whose entry is missing from the dictionary have &#x60;entry: null&#x60;. Smart decks have no items: &#x60;409 not_applicable&#x60; (use &#x60;GET /cards?query&#x3D;&#x60;). Deleted deck: &#x60;410 gone&#x60;. 

### Example

```ts
import {
  Configuration,
  DecksApi,
} from '@kotobaya/api-client';
import type { ListDeckItemsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DecksApi(config);

  const body = {
    // string | Deck id. For a media deck this equals the media id.
    deckId: 01931f40-2000-7000-8000-000000000d01,
    // Set<WordStatusValue> | Only items whose effective word status is one of these (comma-separated). Items without an `entry_seq` have no status and match only when this parameter is absent. (optional)
    status: ["new","learning"],
    // 'position' | 'occurrence_count' | 'freq_rank' | `position` = the deck\'s new-card order (ties by `item_key`); `occurrence_count` = most frequent in this deck first; `freq_rank` = globally most frequent first (NULLs last). Ties by `item_key`. (optional)
    sort: occurrence_count,
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListDeckItemsRequest;

  try {
    const data = await api.listDeckItems(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deckId** | `string` | Deck id. For a media deck this equals the media id. | [Defaults to `undefined`] |
| **status** | `Set<WordStatusValue>` | Only items whose effective word status is one of these (comma-separated). Items without an &#x60;entry_seq&#x60; have no status and match only when this parameter is absent. | [Optional] |
| **sort** | `position`, `occurrence_count`, `freq_rank` | &#x60;position&#x60; &#x3D; the deck\&#39;s new-card order (ties by &#x60;item_key&#x60;); &#x60;occurrence_count&#x60; &#x3D; most frequent in this deck first; &#x60;freq_rank&#x60; &#x3D; globally most frequent first (NULLs last). Ties by &#x60;item_key&#x60;. | [Optional] [Defaults to `undefined`] [Enum: position, occurrence_count, freq_rank] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**DeckItemPage**](DeckItemPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of deck items. |  -  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listDeckLists

> DeckListCatalogue listDeckLists()

Generated lists (JLPT, frequency bands) that can be enabled as &#x60;list&#x60; decks.

The catalogue is computed by the server from the current dictionary release: every loaded vocabulary list (&#x60;ref.vocab_list&#x60;: &#x60;jlpt-n5&#x60; ... &#x60;jlpt-n1&#x60; once the JLPT source is loaded, C-21) and, per loaded frequency list, &#x60;freq-&lt;list id&gt;-&lt;N&gt;&#x60; for N in 1000, 2000, 5000, 10000, 20000 (&#x60;_&#x60; in the list id becomes &#x60;-&#x60;). A list whose &#x60;item_count&#x60; would be 0 is not offered. &#x60;item_count&#x60; equals the number of items a &#x60;deck-build&#x60; of that key publishes against the same release (live entries only). Normative rules: &#x60;contracts/db/VOCAB_LISTS.md&#x60; 4.1. &#x60;deck_id&#x60; is set when a live deck for the key exists. Not paginated; ordered by &#x60;list_key&#x60;. Empty when no dictionary release is loaded. 

### Example

```ts
import {
  Configuration,
  DecksApi,
} from '@kotobaya/api-client';
import type { ListDeckListsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DecksApi(config);

  try {
    const data = await api.listDeckLists();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**DeckListCatalogue**](DeckListCatalogue.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Available lists. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listDecks

> DeckList listDecks(type, includeDeleted)

List decks.

Not paginated. Ordered by &#x60;(priority, id)&#x60; ascending - the order new cards are drawn in.

### Example

```ts
import {
  Configuration,
  DecksApi,
} from '@kotobaya/api-client';
import type { ListDecksRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DecksApi(config);

  const body = {
    // DeckType | Only decks of this type. (optional)
    type: media,
    // boolean | Also return tombstoned rows (`deleted_at` set). (optional)
    includeDeleted: false,
  } satisfies ListDecksRequest;

  try {
    const data = await api.listDecks(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **type** | `DeckType` | Only decks of this type. | [Optional] [Defaults to `undefined`] [Enum: user, media, list, smart] |
| **includeDeleted** | `boolean` | Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [Optional] [Defaults to `false`] |

### Return type

[**DeckList**](DeckList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Decks. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## putDeckItem

> DeckItem putDeckItem(deckId, itemKey, deckItemPut)

Add one item to a user deck, or move it.

Server emits &#x60;deck.item_put&#x60;. Without a body, or without &#x60;position&#x60;: a new item is appended (maximum position + 1024); an existing live item keeps its position. An &#x60;n:&lt;id&gt;&#x60; key whose note has an &#x60;entry_seq&#x60; is &#x60;422&#x60; (use the &#x60;e:&#x60; form); the note or entry must exist. Not a &#x60;user&#x60; deck: &#x60;409 deck_read_only&#x60;. 

### Example

```ts
import {
  Configuration,
  DecksApi,
} from '@kotobaya/api-client';
import type { PutDeckItemRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DecksApi(config);

  const body = {
    // string | Deck id. For a media deck this equals the media id.
    deckId: 01931f40-2000-7000-8000-000000000d01,
    // string | `e:<entry_seq>` or `n:<note uuid, lowercase>`. The colon need not be percent-encoded.
    itemKey: e:1358280,
    // DeckItemPut (optional)
    deckItemPut: {},
  } satisfies PutDeckItemRequest;

  try {
    const data = await api.putDeckItem(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deckId** | `string` | Deck id. For a media deck this equals the media id. | [Defaults to `undefined`] |
| **itemKey** | `string` | &#x60;e:&lt;entry_seq&gt;&#x60; or &#x60;n:&lt;note uuid, lowercase&gt;&#x60;. The colon need not be percent-encoded. | [Defaults to `undefined`] |
| **deckItemPut** | [DeckItemPut](DeckItemPut.md) |  | [Optional] |

### Return type

[**DeckItem**](DeckItem.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The item as stored. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## removeDeckItem

> removeDeckItem(deckId, itemKey)

Remove one item from a user deck (reversible by putting it again).

Server emits &#x60;deck.item_removed&#x60;. Item absent or already removed: &#x60;204&#x60;, no op. Not a &#x60;user&#x60; deck: &#x60;409 deck_read_only&#x60;. 

### Example

```ts
import {
  Configuration,
  DecksApi,
} from '@kotobaya/api-client';
import type { RemoveDeckItemRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DecksApi(config);

  const body = {
    // string | Deck id. For a media deck this equals the media id.
    deckId: 01931f40-2000-7000-8000-000000000d01,
    // string | `e:<entry_seq>` or `n:<note uuid, lowercase>`. The colon need not be percent-encoded.
    itemKey: e:1358280,
  } satisfies RemoveDeckItemRequest;

  try {
    const data = await api.removeDeckItem(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deckId** | `string` | Deck id. For a media deck this equals the media id. | [Defaults to `undefined`] |
| **itemKey** | `string` | &#x60;e:&lt;entry_seq&gt;&#x60; or &#x60;n:&lt;note uuid, lowercase&gt;&#x60;. The colon need not be percent-encoded. | [Defaults to `undefined`] |

### Return type

`void` (Empty response body)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Removed. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateDeck

> Deck updateDeck(deckId, deckPatch)

Rename, reprioritise, enable/disable, set limits or settings.

Server emits one &#x60;deck.updated&#x60; with the registers present: &#x60;name&#x60;, &#x60;priority&#x60;, &#x60;enabled&#x60;, &#x60;new_cards_per_day&#x60;, &#x60;reviews_per_day&#x60;, &#x60;query&#x60; (smart decks only, must parse), and one &#x60;settings.&lt;key&gt;&#x60; per key of &#x60;settings&#x60; (partial map; a &#x60;null&#x60; value removes the key). Works for every deck type. &#x60;type&#x60;, &#x60;media_id&#x60;, &#x60;list_key&#x60; are immutable (&#x60;422&#x60;). An empty body is &#x60;422&#x60;. 

### Example

```ts
import {
  Configuration,
  DecksApi,
} from '@kotobaya/api-client';
import type { UpdateDeckRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DecksApi(config);

  const body = {
    // string | Deck id. For a media deck this equals the media id.
    deckId: 01931f40-2000-7000-8000-000000000d01,
    // DeckPatch
    deckPatch: {"enabled":false},
  } satisfies UpdateDeckRequest;

  try {
    const data = await api.updateDeck(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **deckId** | `string` | Deck id. For a media deck this equals the media id. | [Defaults to `undefined`] |
| **deckPatch** | [DeckPatch](DeckPatch.md) |  | |

### Return type

[**Deck**](Deck.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The updated deck. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

