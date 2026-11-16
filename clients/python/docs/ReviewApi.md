# kotobaya_client.ReviewApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**answer_review**](ReviewApi.md#answer_review) | **POST** /review/answer | Grade a card.
[**create_drill_session**](ReviewApi.md#create_drill_session) | **POST** /drill/session | Build an extra-practice session that does not touch scheduling.
[**get_review_queue**](ReviewApi.md#get_review_queue) | **GET** /review/queue | The next cards to review, built by the shared core&#39;s queue builder.
[**undo_review**](ReviewApi.md#undo_review) | **POST** /review/undo | Undo a review.


# **answer_review**
> ReviewResult answer_review(review_answer_request)

Grade a card.

Server emits `review.logged` and recomputes the card's schedule cache with the shared
core. `mode` decides the review type:

- `schedule` (default): `review_type` = `learning` if the card's state is `new`,
  `learning` or `relearning` at apply time, else `scheduled`. Feeds FSRS. Reviewing a
  card that is not due yet is allowed (FSRS accounts for the short interval).
- `drill`, `cram`: logged with that `review_type`; the schedule does not change.

Rules:
- `rating` is the FSRS rating 1-4. `button`, when sent, must map to `rating`
  (`again`, `nothing`, `something` → 1; `hard` → 2; `good`, `okay` → 3; `easy` → 4);
  otherwise `422`. Clients in five-button mode MUST send `button`.
- `reviewed_at` defaults to the server's now. Past values are accepted (late
  submission) within these bounds, each `422 validation_failed` with
  `errors[0].pointer = /reviewed_at`:
  - more than 5 minutes after the server's now (`code = in_future`);
  - before `FLOOR` = 2020-01-01T00:00:00Z (`code = before_floor`);
  - before `min(card.created_at, now) − 24 h` (`code = before_card`), where `card` is the
    card the review is logged on (the survivor, for a merge loser's id) and `created_at`
    is `wall(hlc)` of the `card.created` op that inserted the row (SYNC.md 7.2, 7.5).
  The op the server mints is exempt from the push-time clock checks (SYNC.md 3.4), so
  these are the only bounds a REST review gets; they reproduce the device guards
  (G1, and G2's implied `reviewed_at >= card.created_at − 24 h`) and the lower bound
  Re-mint uses (SYNC.md 10.4 step 4). The `min` with now keeps a card created by a
  device up to 24 h ahead reviewable at once. The bound uses the replicated
  `created_at`, which comes from the creating device's clock; SYNC.md 3.4 records
  that a pushed review is not checked against it (known issue) - this REST check does
  not change that.
- `utc_offset_min` SHOULD be sent by browsers (`-new Date().getTimezoneOffset()`); when
  absent it is stored as NULL.
- `duration_ms` above 600000 is clamped to 600000.
- `id` is **required**: the client-generated UUIDv7 `review_log.id`, minted once when the
  answer is given and reused unchanged on every retry. Re-sending an existing `id` returns
  the stored review and the card's current state with `200` and emits nothing - whatever
  the rest of the body. This is what makes a retry after a lost response or a `5xx` safe
  (no second `review.logged`, no double FSRS update). An `id` that exists as a review of a
  different card is still answered with the stored review (the body is ignored).
- Card suspended and `mode = schedule`: `409 card_not_reviewable`. Buried cards may be
  answered. Deleted card or note: `410 gone`. A merge loser's id is answered against the
  survivor (`review.card_id` in the response is the survivor's id).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.review_answer_request import ReviewAnswerRequest
from kotobaya_client.models.review_result import ReviewResult
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.ReviewApi(api_client)
    review_answer_request = {"card_id":"01931f40-1a2b-7c3d-8e4f-000000000c01","id":"01931f41-0000-7000-8000-000000000f03","mode":"drill","rating":3} # ReviewAnswerRequest | 

    try:
        # Grade a card.
        api_response = api_instance.answer_review(review_answer_request)
        print("The response of ReviewApi->answer_review:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling ReviewApi->answer_review: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **review_answer_request** | [**ReviewAnswerRequest**](ReviewAnswerRequest.md)|  | 

### Return type

[**ReviewResult**](ReviewResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The logged review and the card after it. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **create_drill_session**
> DrillSession create_drill_session(drill_session_request)

Build an extra-practice session that does not touch scheduling.

Returns an ordered list of cards to drill. Nothing is stored and no op is emitted: the
session exists only in the response (`id` is a fresh UUIDv7 for client-side bookkeeping).
The client shows the items and posts each answer to `POST /review/answer` with
`mode` = the session's `mode` (`drill` or `cram`).

`source` selects the cards:
- `hot_words`: live cards of the current hot words, hottest first.
- `leeches`: live cards with `lapses >= scheduler.leech_threshold`, most lapses first.
- `confusables`: cards of confirmed confusable pairs, partners adjacent. With
  `entry_seq`, only pairs involving that entry's note.
- `query`: cards matching `query` (card search string; required), in `-lapses` order.
- `cards`: exactly `card_ids` (required, 1 to 500), in the given order; unknown, deleted
  and merged-away ids are dropped silently.
Suspended cards are included (drilling a suspended leech is the point); deleted ones
never. An empty `items` list is a normal `200`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.drill_session import DrillSession
from kotobaya_client.models.drill_session_request import DrillSessionRequest
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.ReviewApi(api_client)
    drill_session_request = {"limit":50,"mode":"cram","query":"deck:\"Frieren\"","source":"query"} # DrillSessionRequest | 

    try:
        # Build an extra-practice session that does not touch scheduling.
        api_response = api_instance.create_drill_session(drill_session_request)
        print("The response of ReviewApi->create_drill_session:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling ReviewApi->create_drill_session: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **drill_session_request** | [**DrillSessionRequest**](DrillSessionRequest.md)|  | 

### Return type

[**DrillSession**](DrillSession.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The drill session. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_review_queue**
> ReviewQueue get_review_queue(limit=limit, deck_id=deck_id, kinds=kinds, keep_going=keep_going)

The next cards to review, built by the shared core's queue builder.

For online clients (web, scripts). The server runs the same queue builder the phone runs
locally, at request time, over the server's replica: daily limits (global and per deck),
deck priority order, sibling burying and the proportional mix of reviews and new cards
all come from the core (`ReviewLoop.evaluate`, ADR-037). The response is a prefix of
today's queue: the first `limit` cards in the order they should be shown. It is **not** a
reservation: fetch again after answering (each answer can change what comes next -
learning steps, sibling burying, the mix). Suspended, buried, deleted and merged-away
cards never appear. `loop` (ADR-037) carries the phase and every number a client shows;
with an empty `items`, `loop.phase` alone says which end state this is (see "Review loop"
below). `counts`, `more_available` and `next_learning_due_at` are deprecated and kept
with their old meaning for old clients.

**Review loop (ADR-037).** One rule, in the core (`ReviewLoop`), the same on the server
and in the phone's local API. Vocabulary (the same numbers on every client at the same
instant):
- *ready learning*: learning/relearning cards whose step has elapsed (`due_at` <= now).
  Never capped, never served early.
- *ready reviews*: review cards due before `day_ends_at` (overdue included) within the
  remaining review budget (`scheduler.reviews_per_day`, deck `reviews_per_day`; no
  budget when `scheduler.reviews_unlimited`).
- *ready now* = ready learning + ready reviews. New cards are not part of it.
- *later today*: learning/relearning cards due later, before `day_ends_at`. A hint only.
- *backlog*: every due review an uncapped build would take; *beyond limit* = backlog -
  ready reviews.
- *new today / new goal*: cards introduced today / `scheduler.new_per_day`.

Order of a normal request (`keep_going=false`), always: ready learning first (a card
that came due is the next card); then ready reviews (most overdue first) and new cards
in the **proportional mix** (core `QueueBuilder` rule 5): new cards are spread over the
reviews so that the day's review pool and the day's new goal run out at about the same
time, recomputed on every request from today's reviews; the first card of a fresh day is
the most overdue review. Both modes mix: with `scheduler.reviews_unlimited` the review
side is every ready review, otherwise the reviews left under the cap. New cards per
response are bounded by the day's remaining goal and by `limit` (the batch; an
add_kanji group may overshoot `limit`, never the goal).
Example: 4 reviews ready, goal 2, nothing done today: `R1 N1 R2 R3 N2 R4`.

Phases (`loop.phase`; first match wins):
1. `reviewing`: the served queue has a card (or a deck entry to materialize, C-27).
2. `goal_reached` (only with `scheduler.reviews_unlimited` = true) / `done_for_today`
   (only with it false): only for `keep_going=false`; nothing is ready under the limits
   and `keep_going=true` would return at least one card. Offer Keep going; it never
   leads to a dead end. Which of the two is chosen by the setting, not by which limit
   was hit.
3. `waiting`: nothing ready, keep going would return nothing (or is on and returned
   nothing), and at least one card falls due later today:
   `loop.later_today.next_due_at`. Happens only when no new card is left anywhere.
4. `empty`: nothing now, nothing with keep going, nothing later today.
A learning card that falls due during 2-4 makes the next response `reviewing` again.

Client rules (normative for web, the shell SPA and the CLI):
- **C1 Due first.** A client may hold a page of `items`, but before showing each next
  card it MUST fetch again if `now >= T`, T = the smallest of
  `loop.later_today.next_due_at` of the last response (when not null) and `card.due_at`
  of every `answerReview` response since then whose `card.state` is `learning` or
  `relearning`. So a card that came due is the very next card. An undo
  (`undoReview`) is followed by a fetch.
- **C2 Keep going is persisted per client for the study day.** When the user starts it,
  the client stores the `study_day` of the response it was started on (web and shell
  SPA: `localStorage["kotobaya.keepGoingDay"]`, value = that `study_day` string; CLI
  daemon: its state file; a count-only surface such as a widget needs none) and from
  then on sends `keep_going=true` on **every** `getReviewQueue` it makes (home, review,
  status), also after leaving the review screen and after a restart. It is active iff
  the stored value equals the `study_day` of the latest response; on a mismatch the
  client deletes it and fetches again with `keep_going=false`, discarding the
  mismatching response. Per client, never synced: another browser or the phone keeps
  its own state (off until started there). Not an op, not a setting. (Replaces
  ADR-031's "one visit" scope.)
- **C3 Display.** Ready-now number = `loop.ready.total` when not keeping going;
  `loop.ready.learning + loop.backlog.reviews` when keeping going. Show a backlog line
  only when `loop.backlog.beyond_limit > 0`; a "later today" hint only when
  `loop.later_today.count > 0`; the new bar as `loop.new.today` / `loop.new.goal` (it
  may exceed the goal after keep going: clamp the bar, show the number).
- **C4 Empty `items`** is rendered from `loop.phase` only. `waiting` schedules **one**
  fetch at `next_due_at` + 1 s, and never sooner than 3 s after the previous
  timer-driven fetch (the client's clock may differ from the server's; if the card is
  still not served, the new response reschedules). New clients never read `counts`,
  `more_available` or `next_learning_due_at`. An unknown `phase` value is treated as
  `reviewing` when `items` is non-empty and as `empty` otherwise.
- **C5 Materialization retries** (C-27): `reviewing` with empty `items` and
  `to_materialize > 0` (the three rounds ran out, or the caller is read-only): a caller
  with `review` or `write` fetches again (at most 3 times in a row); a read-only caller
  shows that new cards exist but cannot be created by it.
- A client that sends `kinds` may get `reviewing` with empty `items` (the ready cards
  are of a kind it excluded): for that filter there is nothing to show; `loop` still
  describes the whole queue.

**Keep going (C-26, ADR-031).** With `keep_going=true` the builder runs with
`QueueLimits.keepGoing = true` and `keepGoingNewBatch = limit` (core `QueueBuilder` rule
6): the daily limits (`scheduler.new_per_day`, `scheduler.reviews_per_day`, deck
`new_cards_per_day` / `reviews_per_day`) are lifted, at most about `limit` new cards are
drawn per request (an add_kanji group may overshoot), and the order is ready learning,
then **all** due reviews (most overdue first), then new cards - no mix: with a review cap
the backlog is cleared first, then new cards follow without end; with
`scheduler.reviews_unlimited` new cards follow without end and a review that becomes due
is slotted in next. Everything else (eligibility, sibling burying, deck priority and
order, disabled decks, learning) is unchanged.
Cards not due before `day_ends_at` are never returned (no study-ahead). It is a stateless
per-request flag: nothing is stored, no op is emitted, and the client keeps sending it
on every fetch while the user keeps going. Cards answered this way are ordinary reviews
and count against today's limits as usual. Everything in `loop` except `phase` and
`keep_going` is the same with or without keep going (ADR-033 principle), so every client
shows the same numbers at the same moment whatever mode it is in; so are the deprecated
`counts`.

**No review limit (ADR-034).** If the setting `scheduler.reviews_unlimited` is `true`
(SCHEMA.md 6.3; default `false`), every build (keep going or not) ignores
`scheduler.reviews_per_day` and deck `reviews_per_day`: every review due before
`day_ends_at` is returned and counted in `loop.ready.review` (`loop.reviews.limit` is
null, `loop.backlog.beyond_limit` 0). The new goal and the proportional mix apply as
usual; at the goal the phase is `goal_reached`. It is an ordinary setting written with
`updateSettings`.

**Reading cards retired (ADR-035).** While the setting `cards.reading_enabled` is not
`true` (default `false`; SCHEMA.md 6.4), vocab `reading` cards are never returned, never
counted in `counts`, never bury their siblings, and today's reviews of them use no limit
(core `QueueInput.readingEnabled`, QueueBuilder rule 0). Materialized deck entries get
no `reading` card. Setting it `true` returns the same cards with their history.

**No learn-ahead (ADR-033).** A learning or relearning card is returned only once its
`due_at` has passed (core `QueueLimits.learnAheadMs` = 0 on the server and on devices).
Cards due later today are counted in `loop.later_today` and are only a hint while
anything else can be shown (reviews, new cards, Keep going). Only in phase `waiting`
does the client show a waiting state ("next card in m:ss") and fetch again at
`loop.later_today.next_due_at` (rule C4). A card is never shown again before its
learning step has passed.

**Keep-going scope (normative).** Rule C2 above: per client, persisted for the study
day it was started in, sent on every fetch of that client, dropped when `study_day`
changes.

**Old clients.** A client written before ADR-037 keeps working: `counts`,
`more_available` and `next_learning_due_at` are still sent with their old meaning (see
the schema). What it sees differently: the order (the proportional mix instead of the
fixed-stride interleave), `counts.new` bounded by `limit`, and no per-session "done for
now" (`scheduler.new_per_session` is ignored).

**Deck entries become cards here (C-27, ADR-032).** Deck items `e:<seq>` with no note
are new cards too (core `PendingEntry`; which entries qualify: contracts/core README
"C-27"). The server passes them to the core builder, and when its build draws some
(`toMaterialize` of the build that is **served** - `LoopResult.queue`; never the other
build's - bounded by the same new-card budget as the cards: the day's remaining goal and
the batch `limit`, or with keep going the batch alone; each entry counted as the whole
add_kanji group its card will be, so an entry that would not fit is not drawn) and the
caller holds `review` or `write`, the server creates them before answering, in one op
transaction as the **server device**, exactly as contracts/core README "C-27" defines:
per drawn entry, in the builder's order, the kanji notes the drawing deck's present
`add_kanji` asks for (`note.created` `origin` `auto_kanji` + their `card.created`s;
never a kanji the user deleted), then `note.created` {built-in vocab type, `entry_seq`,
`fields` `{}`, `origin` `deck`} and one `card.created` per kind of the drawing deck's
`card_kinds` (the vocab type's default kinds when that is absent, empty or invalid),
minus kinds whose front would render empty. It then rebuilds and returns that queue; it
materializes again (at most 3 rounds) only while the previous round's notes appear in
`items`. This is the only place an API client's deck entries
become notes: clients never mint these ops or call anything else for it; a device
builds offline from the same core output and mints the same ops itself.
- **Idempotent.** The transaction holds the sync lock and skips an entry a live note
  already holds, so repeated or concurrent fetches never create a second note; a
  fetch that draws nothing emits nothing (and takes no lock).
- **Devices.** A device that created a note for the same entry offline converges by
  SYNC.md 8.1 (the note that held the entry first survives; the other's cards and
  reviews are merged into it).
- **Read-only callers** (`read` only) get the queue without materializing: drawn
  entries are counted in `to_materialize` (and the deprecated `counts.new`), not in
  `items`; `loop.phase` is `reviewing` (rule C5).
- `Kotobaya-Seq` is set when ops were emitted.

Each item carries everything needed to render and grade the card: the card with its note
summary, the dictionary entry (when the note references one that exists), the example
sentence chosen for rotating-example cards, the `review_type` an answer will be logged
with, and a preview of the next due time per rating. Grammar cards also carry
`grammar_sentence` (C-15): the rotated example sentence and span, chosen by the core
picker with the rules on `GrammarCardSentence` (drill sessions use the same rules).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.review_queue import ReviewQueue
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.ReviewApi(api_client)
    limit = 20 # int | Maximum number of cards to return. (optional) (default to 20)
    deck_id = '01931f40-2000-7000-8000-000000000d01' # str | Restrict new cards to this deck and reviews to cards whose note or entry is an item of it. Must be a live deck (`404` otherwise). (optional)
    kinds = ["new","learning","review"] # List[str] | Which queue sections to include, comma-separated. Default all three. (optional) (default to ["new","learning","review"])
    keep_going = False # bool | C-26 (additive). `true` = keep going past today's limits (see the description; core `QueueBuilder` rule 6, with `keepGoingNewBatch = limit`). Default `false` = the normal queue. Scope (ADR-037, rule C2 in the description): a client that started keep going stores the `study_day` it was started in and sends `true` on every fetch while the latest response's `study_day` equals it; on a mismatch it drops the stored value and sends `false`. Per client, never synced. Only the literal values `true` and `false` are accepted; anything else is `422 validation_failed` with `errors[0].pointer = /keep_going` and `code = invalid_value`. Echoed as `loop.keep_going`. Older servers ignore the parameter (they return the normal queue and no `more_available`).  (optional) (default to False)

    try:
        # The next cards to review, built by the shared core's queue builder.
        api_response = api_instance.get_review_queue(limit=limit, deck_id=deck_id, kinds=kinds, keep_going=keep_going)
        print("The response of ReviewApi->get_review_queue:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling ReviewApi->get_review_queue: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **limit** | **int**| Maximum number of cards to return. | [optional] [default to 20]
 **deck_id** | **str**| Restrict new cards to this deck and reviews to cards whose note or entry is an item of it. Must be a live deck (&#x60;404&#x60; otherwise). | [optional] 
 **kinds** | [**List[str]**](str.md)| Which queue sections to include, comma-separated. Default all three. | [optional] [default to [&quot;new&quot;,&quot;learning&quot;,&quot;review&quot;]]
 **keep_going** | **bool**| C-26 (additive). &#x60;true&#x60; &#x3D; keep going past today&#39;s limits (see the description; core &#x60;QueueBuilder&#x60; rule 6, with &#x60;keepGoingNewBatch &#x3D; limit&#x60;). Default &#x60;false&#x60; &#x3D; the normal queue. Scope (ADR-037, rule C2 in the description): a client that started keep going stores the &#x60;study_day&#x60; it was started in and sends &#x60;true&#x60; on every fetch while the latest response&#39;s &#x60;study_day&#x60; equals it; on a mismatch it drops the stored value and sends &#x60;false&#x60;. Per client, never synced. Only the literal values &#x60;true&#x60; and &#x60;false&#x60; are accepted; anything else is &#x60;422 validation_failed&#x60; with &#x60;errors[0].pointer &#x3D; /keep_going&#x60; and &#x60;code &#x3D; invalid_value&#x60;. Echoed as &#x60;loop.keep_going&#x60;. Older servers ignore the parameter (they return the normal queue and no &#x60;more_available&#x60;).  | [optional] [default to False]

### Return type

[**ReviewQueue**](ReviewQueue.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The queue prefix and the review loop&#39;s state (&#x60;loop&#x60;). |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **undo_review**
> ReviewResult undo_review(review_undo_request)

Undo a review.

Server emits `review.undone` for the review named by `review_id` and recomputes the card.

- `review_id` is **required** (any review, whichever device logged it, `manual` rows
  included). There is no implicit "undo my last review": that would not be idempotent
  (a retry after a lost response would undo the review before it). The web app keeps the
  `id` it sent to `POST /review/answer` and passes it here.
- Target already undone: `200` with the current state, no op (an undo cannot be undone).
  Hence retries are safe.
- Unknown `review_id`: `404 not_found`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.review_result import ReviewResult
from kotobaya_client.models.review_undo_request import ReviewUndoRequest
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.ReviewApi(api_client)
    review_undo_request = {"review_id":"01931f41-0000-7000-8000-000000000f01"} # ReviewUndoRequest | 

    try:
        # Undo a review.
        api_response = api_instance.undo_review(review_undo_request)
        print("The response of ReviewApi->undo_review:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling ReviewApi->undo_review: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **review_undo_request** | [**ReviewUndoRequest**](ReviewUndoRequest.md)|  | 

### Return type

[**ReviewResult**](ReviewResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The undone review and the card after recomputation. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

