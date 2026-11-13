# ReviewApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**answerReview**](ReviewApi.md#answerreview) | **POST** /review/answer | Grade a card. |
| [**createDrillSession**](ReviewApi.md#createdrillsession) | **POST** /drill/session | Build an extra-practice session that does not touch scheduling. |
| [**getReviewQueue**](ReviewApi.md#getreviewqueue) | **GET** /review/queue | The next cards to review, built by the shared core\&#39;s queue builder. |
| [**undoReview**](ReviewApi.md#undoreview) | **POST** /review/undo | Undo a review. |



## answerReview

> ReviewResult answerReview(reviewAnswerRequest)

Grade a card.

Server emits &#x60;review.logged&#x60; and recomputes the card\&#39;s schedule cache with the shared core. &#x60;mode&#x60; decides the review type:  - &#x60;schedule&#x60; (default): &#x60;review_type&#x60; &#x3D; &#x60;learning&#x60; if the card\&#39;s state is &#x60;new&#x60;,   &#x60;learning&#x60; or &#x60;relearning&#x60; at apply time, else &#x60;scheduled&#x60;. Feeds FSRS. Reviewing a   card that is not due yet is allowed (FSRS accounts for the short interval). - &#x60;drill&#x60;, &#x60;cram&#x60;: logged with that &#x60;review_type&#x60;; the schedule does not change.  Rules: - &#x60;rating&#x60; is the FSRS rating 1-4. &#x60;button&#x60;, when sent, must map to &#x60;rating&#x60;   (&#x60;again&#x60;, &#x60;nothing&#x60;, &#x60;something&#x60; → 1; &#x60;hard&#x60; → 2; &#x60;good&#x60;, &#x60;okay&#x60; → 3; &#x60;easy&#x60; → 4);   otherwise &#x60;422&#x60;. Clients in five-button mode MUST send &#x60;button&#x60;. - &#x60;reviewed_at&#x60; defaults to the server\&#39;s now. Past values are accepted (late   submission) within these bounds, each &#x60;422 validation_failed&#x60; with   &#x60;errors[0].pointer &#x3D; /reviewed_at&#x60;:   - more than 5 minutes after the server\&#39;s now (&#x60;code &#x3D; in_future&#x60;);   - before &#x60;FLOOR&#x60; &#x3D; 2020-01-01T00:00:00Z (&#x60;code &#x3D; before_floor&#x60;);   - before &#x60;min(card.created_at, now) − 24 h&#x60; (&#x60;code &#x3D; before_card&#x60;), where &#x60;card&#x60; is the     card the review is logged on (the survivor, for a merge loser\&#39;s id) and &#x60;created_at&#x60;     is &#x60;wall(hlc)&#x60; of the &#x60;card.created&#x60; op that inserted the row (SYNC.md 7.2, 7.5).   The op the server mints is exempt from the push-time clock checks (SYNC.md 3.4), so   these are the only bounds a REST review gets; they reproduce the device guards   (G1, and G2\&#39;s implied &#x60;reviewed_at &gt;&#x3D; card.created_at − 24 h&#x60;) and the lower bound   Re-mint uses (SYNC.md 10.4 step 4). The &#x60;min&#x60; with now keeps a card created by a   device up to 24 h ahead reviewable at once. The bound uses the replicated   &#x60;created_at&#x60;, which comes from the creating device\&#39;s clock; SYNC.md 3.4 records   that a pushed review is not checked against it (known issue) - this REST check does   not change that. - &#x60;utc_offset_min&#x60; SHOULD be sent by browsers (&#x60;-new Date().getTimezoneOffset()&#x60;); when   absent it is stored as NULL. - &#x60;duration_ms&#x60; above 600000 is clamped to 600000. - &#x60;id&#x60; is **required**: the client-generated UUIDv7 &#x60;review_log.id&#x60;, minted once when the   answer is given and reused unchanged on every retry. Re-sending an existing &#x60;id&#x60; returns   the stored review and the card\&#39;s current state with &#x60;200&#x60; and emits nothing - whatever   the rest of the body. This is what makes a retry after a lost response or a &#x60;5xx&#x60; safe   (no second &#x60;review.logged&#x60;, no double FSRS update). An &#x60;id&#x60; that exists as a review of a   different card is still answered with the stored review (the body is ignored). - Card suspended and &#x60;mode &#x3D; schedule&#x60;: &#x60;409 card_not_reviewable&#x60;. Buried cards may be   answered. Deleted card or note: &#x60;410 gone&#x60;. A merge loser\&#39;s id is answered against the   survivor (&#x60;review.card_id&#x60; in the response is the survivor\&#39;s id). 

### Example

```ts
import {
  Configuration,
  ReviewApi,
} from '@kotobaya/api-client';
import type { AnswerReviewRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ReviewApi(config);

  const body = {
    // ReviewAnswerRequest
    reviewAnswerRequest: {"card_id":"01931f40-1a2b-7c3d-8e4f-000000000c01","id":"01931f41-0000-7000-8000-000000000f03","mode":"drill","rating":3},
  } satisfies AnswerReviewRequest;

  try {
    const data = await api.answerReview(body);
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
| **reviewAnswerRequest** | [ReviewAnswerRequest](ReviewAnswerRequest.md) |  | |

### Return type

[**ReviewResult**](ReviewResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The logged review and the card after it. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## createDrillSession

> DrillSession createDrillSession(drillSessionRequest)

Build an extra-practice session that does not touch scheduling.

Returns an ordered list of cards to drill. Nothing is stored and no op is emitted: the session exists only in the response (&#x60;id&#x60; is a fresh UUIDv7 for client-side bookkeeping). The client shows the items and posts each answer to &#x60;POST /review/answer&#x60; with &#x60;mode&#x60; &#x3D; the session\&#39;s &#x60;mode&#x60; (&#x60;drill&#x60; or &#x60;cram&#x60;).  &#x60;source&#x60; selects the cards: - &#x60;hot_words&#x60;: live cards of the current hot words, hottest first. - &#x60;leeches&#x60;: live cards with &#x60;lapses &gt;&#x3D; scheduler.leech_threshold&#x60;, most lapses first. - &#x60;confusables&#x60;: cards of confirmed confusable pairs, partners adjacent. With   &#x60;entry_seq&#x60;, only pairs involving that entry\&#39;s note. - &#x60;query&#x60;: cards matching &#x60;query&#x60; (card search string; required), in &#x60;-lapses&#x60; order. - &#x60;cards&#x60;: exactly &#x60;card_ids&#x60; (required, 1 to 500), in the given order; unknown, deleted   and merged-away ids are dropped silently. Suspended cards are included (drilling a suspended leech is the point); deleted ones never. An empty &#x60;items&#x60; list is a normal &#x60;200&#x60;. 

### Example

```ts
import {
  Configuration,
  ReviewApi,
} from '@kotobaya/api-client';
import type { CreateDrillSessionRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ReviewApi(config);

  const body = {
    // DrillSessionRequest
    drillSessionRequest: {"limit":50,"mode":"cram","query":"deck:\"Frieren\"","source":"query"},
  } satisfies CreateDrillSessionRequest;

  try {
    const data = await api.createDrillSession(body);
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
| **drillSessionRequest** | [DrillSessionRequest](DrillSessionRequest.md) |  | |

### Return type

[**DrillSession**](DrillSession.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The drill session. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getReviewQueue

> ReviewQueue getReviewQueue(limit, deckId, kinds)

The next cards to review, built by the shared core\&#39;s queue builder.

For online clients (web, scripts). The server runs the same queue builder the phone runs locally, at request time, over the server\&#39;s replica: daily limits (global and per deck), deck priority order, sibling burying, interleaving and the per-session new-card cap all come from the core. The response is a prefix of today\&#39;s queue: the first &#x60;limit&#x60; cards in the order they should be shown. It is **not** a reservation: fetch again after answering (each answer can change what comes next - learning steps, sibling burying). Suspended, buried, deleted and merged-away cards never appear. Empty &#x60;items&#x60; with all counts zero means done for the study day.  Each item carries everything needed to render and grade the card: the card with its note summary, the dictionary entry (when the note references one that exists), the example sentence chosen for rotating-example cards, the &#x60;review_type&#x60; an answer will be logged with, and a preview of the next due time per rating. Grammar cards also carry &#x60;grammar_sentence&#x60; (C-15): the rotated example sentence and span, chosen by the core picker with the rules on &#x60;GrammarCardSentence&#x60; (drill sessions use the same rules). 

### Example

```ts
import {
  Configuration,
  ReviewApi,
} from '@kotobaya/api-client';
import type { GetReviewQueueRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ReviewApi(config);

  const body = {
    // number | Maximum number of cards to return. (optional)
    limit: 20,
    // string | Restrict new cards to this deck and reviews to cards whose note or entry is an item of it. Must be a live deck (`404` otherwise). (optional)
    deckId: 01931f40-2000-7000-8000-000000000d01,
    // Set<'new' | 'learning' | 'review'> | Which queue sections to include, comma-separated. Default all three. (optional)
    kinds: ["learning","review"],
  } satisfies GetReviewQueueRequest;

  try {
    const data = await api.getReviewQueue(body);
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
| **limit** | `number` | Maximum number of cards to return. | [Optional] [Defaults to `20`] |
| **deckId** | `string` | Restrict new cards to this deck and reviews to cards whose note or entry is an item of it. Must be a live deck (&#x60;404&#x60; otherwise). | [Optional] [Defaults to `undefined`] |
| **kinds** | `new`, `learning`, `review` | Which queue sections to include, comma-separated. Default all three. | [Optional] [Enum: new, learning, review] |

### Return type

[**ReviewQueue**](ReviewQueue.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The queue prefix and today\&#39;s remaining counts. |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## undoReview

> ReviewResult undoReview(reviewUndoRequest)

Undo a review.

Server emits &#x60;review.undone&#x60; for the review named by &#x60;review_id&#x60; and recomputes the card.  - &#x60;review_id&#x60; is **required** (any review, whichever device logged it, &#x60;manual&#x60; rows   included). There is no implicit \&quot;undo my last review\&quot;: that would not be idempotent   (a retry after a lost response would undo the review before it). The web app keeps the   &#x60;id&#x60; it sent to &#x60;POST /review/answer&#x60; and passes it here. - Target already undone: &#x60;200&#x60; with the current state, no op (an undo cannot be undone).   Hence retries are safe. - Unknown &#x60;review_id&#x60;: &#x60;404 not_found&#x60;. 

### Example

```ts
import {
  Configuration,
  ReviewApi,
} from '@kotobaya/api-client';
import type { UndoReviewRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ReviewApi(config);

  const body = {
    // ReviewUndoRequest
    reviewUndoRequest: {"review_id":"01931f41-0000-7000-8000-000000000f01"},
  } satisfies UndoReviewRequest;

  try {
    const data = await api.undoReview(body);
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
| **reviewUndoRequest** | [ReviewUndoRequest](ReviewUndoRequest.md) |  | |

### Return type

[**ReviewResult**](ReviewResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The undone review and the card after recomputation. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

