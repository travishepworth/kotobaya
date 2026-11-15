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

> ReviewQueue getReviewQueue(limit, deckId, kinds, keepGoing)

The next cards to review, built by the shared core\&#39;s queue builder.

For online clients (web, scripts). The server runs the same queue builder the phone runs locally, at request time, over the server\&#39;s replica: daily limits (global and per deck), deck priority order, sibling burying, interleaving and the per-session new-card cap all come from the core. The response is a prefix of today\&#39;s queue: the first &#x60;limit&#x60; cards in the order they should be shown. It is **not** a reservation: fetch again after answering (each answer can change what comes next - learning steps, sibling burying). Suspended, buried, deleted and merged-away cards never appear. An empty &#x60;items&#x60; is one of the end states listed under \&quot;Keep going\&quot; below; tell them apart with &#x60;more_available&#x60; and &#x60;counts.learning&#x60;.  **Keep going (C-26, ADR-031).** With &#x60;keep_going&#x3D;true&#x60; the builder runs with &#x60;QueueLimits.keepGoing &#x3D; true&#x60; and &#x60;keepGoingNewBatch &#x3D; limit&#x60; (core &#x60;QueueBuilder&#x60; rule 6): the daily and session limits (&#x60;scheduler.new_per_day&#x60;, &#x60;scheduler.reviews_per_day&#x60;, &#x60;scheduler.new_per_session&#x60;, deck &#x60;new_cards_per_day&#x60; / &#x60;reviews_per_day&#x60;) are lifted, at most about &#x60;limit&#x60; new cards are drawn per request (an add_kanji group may overshoot), reviews due today come before new cards, and everything else (eligibility, sibling burying, deck priority and order, disabled decks, learning) is unchanged. Cards not due before &#x60;day_ends_at&#x60; are never returned (no study-ahead). It is a stateless per-request flag: nothing is stored, no op is emitted, and the client keeps sending it on every fetch while the user keeps going. Cards answered this way are ordinary reviews and count against today\&#39;s limits as usual. &#x60;counts&#x60; are the same with or without keep going (ADR-033): what remains today under the limits, so every client shows the same numbers at the same moment whatever mode it is in.  **No learn-ahead (ADR-033).** A learning or relearning card is returned only once its &#x60;due_at&#x60; has passed (core &#x60;QueueLimits.learnAheadMs&#x60; &#x3D; 0 on the server and on devices). When nothing else is left but learning cards due later today, &#x60;items&#x60; is empty and &#x60;next_learning_due_at&#x60; says when the next one falls due: show a waiting state (\&quot;next card in m:ss\&quot;) and fetch again at that instant. A card is never shown again before its learning step has passed.  **Keep-going scope (normative).** Keep going is UI state scoped to one visit to the review screen. It is cleared when the user leaves the review screen, when the study day (&#x60;study_day&#x60; of the responses; the local date of &#x60;StudyDay&#x60; on a device) differs from the one it was started in, and on app restart. The normal Review entry point and the Today counts always use &#x60;keep_going&#x3D;false&#x60;. A client therefore never persists the flag (no app-lifetime store, no local storage) and compares each keep-going response\&#39;s &#x60;study_day&#x60; with the day keep going started; on a mismatch it drops the flag and re-fetches with &#x60;keep_going&#x3D;false&#x60;.  The three end states for a client: - &#x60;items&#x60; non-empty: show them. - &#x60;items&#x60; empty, &#x60;more_available&#x60; true (only possible with &#x60;keep_going&#x3D;false&#x60;): done   under the limits; offer Keep going (re-fetch with &#x60;keep_going&#x3D;true&#x60;). This includes   the case where only the per-session new-card cap (&#x60;scheduler.new_per_session&#x60;) is   reached, so the UI says \&quot;done for now\&quot;, not \&quot;done for today\&quot;. - &#x60;items&#x60; empty, &#x60;more_available&#x60; false: nothing left to show now for this &#x60;kinds&#x60;,   with or without keep going. If &#x60;next_learning_due_at&#x60; is set, a learning card falls   due then (wait and fetch again at that instant); otherwise the day is done. - &#x60;items&#x60; empty and &#x60;to_materialize&#x60; &gt; 0 (C-27; only for a caller without the &#x60;review&#x60;   and &#x60;write&#x60; scopes): new cards exist but are deck entries without notes yet; a   caller that may review gets them materialized by its own fetch. A keep-going client ends the session only on a keep-going response with empty &#x60;items&#x60; and &#x60;to_materialize&#x60; 0; anything else is followed by another fetch once the local queue empties.  **Deck entries become cards here (C-27, ADR-032).** Deck items &#x60;e:&lt;seq&gt;&#x60; with no note are new cards too (core &#x60;PendingEntry&#x60;; which entries qualify: contracts/core README \&quot;C-27\&quot;). The server passes them to the core builder, and when its build draws some (&#x60;ReviewQueue.toMaterialize&#x60;, bounded by the same new-card budget as the cards: the daily and session limits, or the keep-going batch; each entry counted as the whole add_kanji group its card will be, so an entry that would not fit is not drawn) and the caller holds &#x60;review&#x60; or &#x60;write&#x60;, the server creates them before answering, in one op transaction as the **server device**, exactly as contracts/core README \&quot;C-27\&quot; defines: per drawn entry, in the builder\&#39;s order, the kanji notes the drawing deck\&#39;s present &#x60;add_kanji&#x60; asks for (&#x60;note.created&#x60; &#x60;origin&#x60; &#x60;auto_kanji&#x60; + their &#x60;card.created&#x60;s; never a kanji the user deleted), then &#x60;note.created&#x60; {built-in vocab type, &#x60;entry_seq&#x60;, &#x60;fields&#x60; &#x60;{}&#x60;, &#x60;origin&#x60; &#x60;deck&#x60;} and one &#x60;card.created&#x60; per kind of the drawing deck\&#39;s &#x60;card_kinds&#x60; (the vocab type\&#39;s default kinds when that is absent, empty or invalid), minus kinds whose front would render empty. It then rebuilds and returns that queue; it materializes again (at most 3 rounds) only while the previous round\&#39;s notes appear in &#x60;items&#x60;. This is the only place an API client\&#39;s deck entries become notes: clients never mint these ops or call anything else for it; a device builds offline from the same core output and mints the same ops itself. - **Idempotent.** The transaction holds the sync lock and skips an entry a live note   already holds, so repeated or concurrent fetches never create a second note; a   fetch that draws nothing emits nothing (and takes no lock). - **Devices.** A device that created a note for the same entry offline converges by   SYNC.md 8.1 (the note that held the entry first survives; the other\&#39;s cards and   reviews are merged into it). - **Read-only callers** (&#x60;read&#x60; only) get the queue without materializing: drawn   entries are counted in &#x60;counts.new&#x60; and &#x60;to_materialize&#x60;, not in &#x60;items&#x60;. - &#x60;Kotobaya-Seq&#x60; is set when ops were emitted.  Each item carries everything needed to render and grade the card: the card with its note summary, the dictionary entry (when the note references one that exists), the example sentence chosen for rotating-example cards, the &#x60;review_type&#x60; an answer will be logged with, and a preview of the next due time per rating. Grammar cards also carry &#x60;grammar_sentence&#x60; (C-15): the rotated example sentence and span, chosen by the core picker with the rules on &#x60;GrammarCardSentence&#x60; (drill sessions use the same rules). 

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
    // boolean | C-26 (additive). `true` = keep going past today\'s limits (see the description; core `QueueBuilder` rule 6, with `keepGoingNewBatch = limit`). Default `false` = the normal queue, exactly as before C-26. Keep going is UI state scoped to one visit to the review screen: it is cleared when the user leaves the review screen, when `study_day` differs from the day it was started in, and on app restart; the normal Review entry point and the Today counts always send `false` (see \"Keep-going scope\" in the description). Only the literal values `true` and `false` are accepted; anything else is `422 validation_failed` with `errors[0].pointer = /keep_going` and `code = invalid_value`. Older servers ignore the parameter (they return the normal queue and no `more_available`).  (optional)
    keepGoing: true,
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
| **keepGoing** | `boolean` | C-26 (additive). &#x60;true&#x60; &#x3D; keep going past today\&#39;s limits (see the description; core &#x60;QueueBuilder&#x60; rule 6, with &#x60;keepGoingNewBatch &#x3D; limit&#x60;). Default &#x60;false&#x60; &#x3D; the normal queue, exactly as before C-26. Keep going is UI state scoped to one visit to the review screen: it is cleared when the user leaves the review screen, when &#x60;study_day&#x60; differs from the day it was started in, and on app restart; the normal Review entry point and the Today counts always send &#x60;false&#x60; (see \&quot;Keep-going scope\&quot; in the description). Only the literal values &#x60;true&#x60; and &#x60;false&#x60; are accepted; anything else is &#x60;422 validation_failed&#x60; with &#x60;errors[0].pointer &#x3D; /keep_going&#x60; and &#x60;code &#x3D; invalid_value&#x60;. Older servers ignore the parameter (they return the normal queue and no &#x60;more_available&#x60;).  | [Optional] [Defaults to `false`] |

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

