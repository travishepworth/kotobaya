# CardsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**bulkCards**](CardsApi.md#bulkcards) | **POST** /cards/bulk | Apply one action to many cards (suspend, tag, move, delete, ...). |
| [**bulkScheduleCards**](CardsApi.md#bulkschedulecards) | **POST** /cards/bulk/schedule | Reschedule or reset many cards (manual review-log rows). |
| [**createCard**](CardsApi.md#createcard) | **POST** /cards | Add an optional card kind to an existing note. |
| [**deleteCard**](CardsApi.md#deletecard) | **DELETE** /cards/{card_id} | Delete one card (permanent tombstone); the note and its other cards stay. |
| [**getCard**](CardsApi.md#getcard) | **GET** /cards/{card_id} | One card with its note summary. |
| [**listCardReviews**](CardsApi.md#listcardreviews) | **GET** /cards/{card_id}/reviews | Review history of a card (its whole replay set plus drills and crams). |
| [**listCards**](CardsApi.md#listcards) | **GET** /cards | Find cards with the card search language. |
| [**updateCard**](CardsApi.md#updatecard) | **PATCH** /cards/{card_id} | Suspend, bury, flag or re-preset one card. |



## bulkCards

> CardBulkResult bulkCards(cardBulkRequest)

Apply one action to many cards (suspend, tag, move, delete, ...).

Select cards with exactly one of &#x60;card_ids&#x60; (1 to 5000 ids) or &#x60;query&#x60; (card search string). Unknown, deleted and merged-away ids in &#x60;card_ids&#x60; are skipped and counted in &#x60;skipped&#x60; (merge losers are **not** redirected in bulk). A selector matching more than 5000 cards is &#x60;422 bulk_too_large&#x60;; nothing is applied. Matching zero cards is a successful no-op. All ops of one call are applied in one transaction, ordered by card id.  | &#x60;action.type&#x60; | Required params | Server emits, per matched card unless noted | |---|---|---| | &#x60;suspend&#x60; | - | &#x60;card.suspended&#x60; | | &#x60;unsuspend&#x60; | - | &#x60;card.unsuspended&#x60; | | &#x60;bury&#x60; | &#x60;until&#x60; (optional; default &#x3D; start of the next study day) | &#x60;card.buried&#x60; | | &#x60;unbury&#x60; | - | &#x60;card.unburied&#x60; | | &#x60;flag&#x60; | &#x60;flags&#x60; (0-7; 0 clears) | &#x60;card.flag_set&#x60; | | &#x60;set_preset&#x60; | &#x60;preset_id&#x60; (live preset) | &#x60;card.preset_set&#x60; | | &#x60;add_tags&#x60;, &#x60;remove_tags&#x60; | &#x60;tags&#x60; (non-empty) | one &#x60;note.updated&#x60; (register &#x60;tags&#x60;) per **distinct note** of the matched cards | | &#x60;move&#x60; | &#x60;to_deck_id&#x60; (live &#x60;user&#x60; deck), optional &#x60;from_deck_id&#x60; (live &#x60;user&#x60; deck) | per distinct note: &#x60;deck.item_put&#x60; into &#x60;to_deck_id&#x60; (key &#x60;e:&lt;seq&gt;&#x60; or &#x60;n:&lt;note id&gt;&#x60;, appended in card-id order at max position + 1024 each) and, with &#x60;from_deck_id&#x60;, &#x60;deck.item_removed&#x60; there if the item exists | | &#x60;delete&#x60; | - | &#x60;card.deleted&#x60; |  Cards already in the target state still get an op (it bumps the clock) except: &#x60;delete&#x60; on a deleted card, and tag actions that would not change a note\&#39;s tag set - those are counted in &#x60;skipped&#x60;. An &#x60;add_tags&#x60; that would give any matched note more than 200 tags is &#x60;422 validation_failed&#x60; (error &#x60;out_of_range&#x60; at &#x60;/action/tags&#x60;); nothing is applied (C-2). Params not listed for the action type are &#x60;422&#x60;. A &#x60;to_deck_id&#x60;/&#x60;from_deck_id&#x60; that is not a live &#x60;user&#x60; deck is &#x60;409 deck_read_only&#x60;. With &#x60;dry_run: true&#x60; nothing is emitted and the counts say what would happen. Repeating a call is safe: register actions converge.  Reschedule and reset write review history (&#x60;review.logged&#x60;) and therefore need the &#x60;review&#x60; scope; they are the separate operation &#x60;POST /cards/bulk/schedule&#x60;. 

### Example

```ts
import {
  Configuration,
  CardsApi,
} from '@kotobaya/api-client';
import type { BulkCardsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new CardsApi(config);

  const body = {
    // CardBulkRequest
    cardBulkRequest: {"action":{"from_deck_id":"01931f40-2000-7000-8000-000000000d02","to_deck_id":"01931f40-2000-7000-8000-000000000d01","type":"move"},"query":"deck:\"Inbox\""},
  } satisfies BulkCardsRequest;

  try {
    const data = await api.bulkCards(body);
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
| **cardBulkRequest** | [CardBulkRequest](CardBulkRequest.md) |  | |

### Return type

[**CardBulkResult**](CardBulkResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | What was (or with &#x60;dry_run&#x60; would be) done. |  * Kotobaya-Seq -  <br>  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## bulkScheduleCards

> CardBulkResult bulkScheduleCards(cardBulkScheduleRequest)

Reschedule or reset many cards (manual review-log rows).

Same selector, limits, ordering, transaction and &#x60;dry_run&#x60; rules as &#x60;POST /cards/bulk&#x60;. Needs the &#x60;review&#x60; scope, like every other way of writing &#x60;review_log&#x60; (&#x60;POST /sync/push&#x60; needs &#x60;review&#x60; for &#x60;review.*&#x60; ops; &#x60;POST /review/answer&#x60;).  | &#x60;action.type&#x60; | Required params | Server emits, per matched card | |---|---|---| | &#x60;reschedule&#x60; | &#x60;due_at&#x60; | &#x60;review.logged&#x60; with &#x60;review_type &#x3D; manual&#x60;, &#x60;manual_action &#x3D; set_due&#x60;, &#x60;manual_due_at &#x3D; due_at&#x60; | | &#x60;reset&#x60; | - | &#x60;review.logged&#x60; with &#x60;review_type &#x3D; manual&#x60;, &#x60;manual_action &#x3D; reset&#x60; |  - &#x60;reset&#x60; of a card in state &#x60;new&#x60; is skipped (counted in &#x60;skipped&#x60;). - &#x60;reschedule&#x60; is &#x60;422&#x60; for the whole call if any matched card is &#x60;new&#x60; (a new card has no   memory state to keep); nothing is applied. - Suspended cards are processed (the manual row changes the schedule cache; the card   stays suspended). - Repeating a call adds another manual row per card with the same effect on the   schedule (manual rows are not deduplicated). Each manual row can be undone with   &#x60;POST /review/undo&#x60; by its id (&#x60;GET /cards/{card_id}/reviews&#x60;). 

### Example

```ts
import {
  Configuration,
  CardsApi,
} from '@kotobaya/api-client';
import type { BulkScheduleCardsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new CardsApi(config);

  const body = {
    // CardBulkScheduleRequest
    cardBulkScheduleRequest: {"action":{"due_at":"2026-11-20T19:00:00.000Z","type":"reschedule"},"dry_run":true,"query":"is:due kind:reading"},
  } satisfies BulkScheduleCardsRequest;

  try {
    const data = await api.bulkScheduleCards(body);
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
| **cardBulkScheduleRequest** | [CardBulkScheduleRequest](CardBulkScheduleRequest.md) |  | |

### Return type

[**CardBulkResult**](CardBulkResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | What was (or with &#x60;dry_run&#x60; would be) done. |  * Kotobaya-Seq -  <br>  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## createCard

> Card createCard(cardCreate)

Add an optional card kind to an existing note.

Server emits &#x60;card.created&#x60;. &#x60;kind&#x60; must be a key of the note type\&#39;s &#x60;templates&#x60;. If the note already has a live card of that kind it is returned with &#x60;200&#x60; and nothing is emitted (REST never creates a duplicate &#x60;(note, kind)&#x60;). A merge loser\&#39;s &#x60;note_id&#x60; acts on the survivor. A deleted note is &#x60;410 gone&#x60;. 

### Example

```ts
import {
  Configuration,
  CardsApi,
} from '@kotobaya/api-client';
import type { CreateCardRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new CardsApi(config);

  const body = {
    // CardCreate
    cardCreate: {"kind":"listening","note_id":"01931f40-1a2b-7c3d-8e4f-000000000a01"},
  } satisfies CreateCardRequest;

  try {
    const data = await api.createCard(body);
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
| **cardCreate** | [CardCreate](CardCreate.md) |  | |

### Return type

[**Card**](Card.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The card already existed (same &#x60;id&#x60;, or same note and kind). |  -  |
| **201** | Created. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## deleteCard

> deleteCard(cardId)

Delete one card (permanent tombstone); the note and its other cards stay.

Server emits &#x60;card.deleted&#x60;. Already deleted or a merge loser - &#x60;204&#x60;, no op.

### Example

```ts
import {
  Configuration,
  CardsApi,
} from '@kotobaya/api-client';
import type { DeleteCardRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new CardsApi(config);

  const body = {
    // string | Card id.
    cardId: 01931f40-1a2b-7c3d-8e4f-000000000c01,
  } satisfies DeleteCardRequest;

  try {
    const data = await api.deleteCard(body);
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
| **cardId** | `string` | Card id. | [Defaults to `undefined`] |

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
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getCard

> Card getCard(cardId, includeDeleted)

One card with its note summary.

A merge loser\&#39;s id returns the survivor (&#x60;Content-Location&#x60; set). Deleted card, or card of a deleted note - &#x60;410 gone&#x60; unless &#x60;include_deleted&#x3D;true&#x60;.

### Example

```ts
import {
  Configuration,
  CardsApi,
} from '@kotobaya/api-client';
import type { GetCardRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new CardsApi(config);

  const body = {
    // string | Card id.
    cardId: 01931f40-1a2b-7c3d-8e4f-000000000c01,
    // boolean | Also return tombstoned rows (`deleted_at` set). (optional)
    includeDeleted: false,
  } satisfies GetCardRequest;

  try {
    const data = await api.getCard(body);
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
| **cardId** | `string` | Card id. | [Defaults to `undefined`] |
| **includeDeleted** | `boolean` | Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [Optional] [Defaults to `false`] |

### Return type

[**Card**](Card.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The card (or its merge survivor). |  * Content-Location -  <br>  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listCardReviews

> ReviewPage listCardReviews(cardId, limit, cursor)

Review history of a card (its whole replay set plus drills and crams).

Rows of &#x60;review_log&#x60; whose &#x60;card_id&#x60; is this card or any card merged into it, every &#x60;review_type&#x60;, newest first by &#x60;(reviewed_at, hlc, id)&#x60; descending - the reverse of the replay order (SYNC.md 9.1), so a late or re-minted review (SYNC.md 10.4) sits where it was answered, not where it was logged. Undone rows are included (&#x60;undone_at&#x60; set) so the history is complete. A merge loser\&#39;s id resolves to the survivor. Works for deleted cards (history is never deleted). 

### Example

```ts
import {
  Configuration,
  CardsApi,
} from '@kotobaya/api-client';
import type { ListCardReviewsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new CardsApi(config);

  const body = {
    // string | Card id.
    cardId: 01931f40-1a2b-7c3d-8e4f-000000000c01,
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListCardReviewsRequest;

  try {
    const data = await api.listCardReviews(body);
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
| **cardId** | `string` | Card id. | [Defaults to `undefined`] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**ReviewPage**](ReviewPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of review log rows. |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listCards

> CardPage listCards(query, sort, limit, cursor)

Find cards with the card search language.

&#x60;query&#x60; is a string in the card search language, parsed by the shared core (e.g. &#x60;deck:\&quot;Frieren\&quot; is:due tag:leech lapses&gt;3 kanji:績 rated:7:1 kind:reading&#x60;). Absent or empty &#x3D; all live cards. A string that does not parse is &#x60;422 invalid_query&#x60; with the UTF-16 offset of the error in &#x60;errors[0].position&#x60;. Cards of deleted notes, deleted cards and merge losers never match. Each card embeds a &#x60;note&#x60; summary. &#x60;is:due&#x60; and similar relative terms are evaluated at request time for the current study day. 

### Example

```ts
import {
  Configuration,
  CardsApi,
} from '@kotobaya/api-client';
import type { ListCardsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new CardsApi(config);

  const body = {
    // string | Card search string (grammar owned by the shared core). Maximum 2000 characters. (optional)
    query: deck:"Frieren" is:due lapses>3,
    // 'created_at' | '-created_at' | 'due_at' | '-due_at' | 'updated_at' | '-updated_at' | 'lapses' | '-lapses' | 'difficulty' | '-difficulty' | 'stability' | '-stability' | 'last_review_at' | '-last_review_at' | Sort key; a leading `-` means descending. Ties are broken by card `id` ascending. NULL values sort last in either direction. (optional)
    sort: due_at,
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListCardsRequest;

  try {
    const data = await api.listCards(body);
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
| **query** | `string` | Card search string (grammar owned by the shared core). Maximum 2000 characters. | [Optional] [Defaults to `undefined`] |
| **sort** | `created_at`, `-created_at`, `due_at`, `-due_at`, `updated_at`, `-updated_at`, `lapses`, `-lapses`, `difficulty`, `-difficulty`, `stability`, `-stability`, `last_review_at`, `-last_review_at` | Sort key; a leading &#x60;-&#x60; means descending. Ties are broken by card &#x60;id&#x60; ascending. NULL values sort last in either direction. | [Optional] [Defaults to `undefined`] [Enum: created_at, -created_at, due_at, -due_at, updated_at, -updated_at, lapses, -lapses, difficulty, -difficulty, stability, -stability, last_review_at, -last_review_at] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**CardPage**](CardPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of cards. |  -  |
| **422** | The card search string does not parse (or another validation error; see &#x60;code&#x60;). |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateCard

> Card updateCard(cardId, cardPatch)

Suspend, bury, flag or re-preset one card.

Server emits one op per property present, in this order: &#x60;suspended&#x60; → &#x60;card.suspended&#x60; (true) or &#x60;card.unsuspended&#x60; (false); &#x60;buried_until&#x60; → &#x60;card.buried&#x60; (an instant) or &#x60;card.unburied&#x60; (&#x60;null&#x60;); &#x60;flags&#x60; → &#x60;card.flag_set&#x60;; &#x60;preset_id&#x60; → &#x60;card.preset_set&#x60; (must be a live preset). An empty body is &#x60;422&#x60;. A merge loser\&#39;s id acts on the survivor. Schedule properties cannot be patched (use &#x60;POST /cards/bulk/schedule&#x60; &#x60;reschedule&#x60;/&#x60;reset&#x60; or review the card). 

### Example

```ts
import {
  Configuration,
  CardsApi,
} from '@kotobaya/api-client';
import type { UpdateCardRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new CardsApi(config);

  const body = {
    // string | Card id.
    cardId: 01931f40-1a2b-7c3d-8e4f-000000000c01,
    // CardPatch
    cardPatch: {"buried_until":"2026-11-13T19:00:00.000Z"},
  } satisfies UpdateCardRequest;

  try {
    const data = await api.updateCard(body);
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
| **cardId** | `string` | Card id. | [Defaults to `undefined`] |
| **cardPatch** | [CardPatch](CardPatch.md) |  | |

### Return type

[**Card**](Card.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The updated card. |  * Content-Location -  <br>  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

