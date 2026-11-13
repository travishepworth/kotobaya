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
> ReviewQueue get_review_queue(limit=limit, deck_id=deck_id, kinds=kinds)

The next cards to review, built by the shared core's queue builder.

For online clients (web, scripts). The server runs the same queue builder the phone runs
locally, at request time, over the server's replica: daily limits (global and per deck),
deck priority order, sibling burying, interleaving and the per-session new-card cap all
come from the core. The response is a prefix of today's queue: the first `limit` cards
in the order they should be shown. It is **not** a reservation: fetch again after
answering (each answer can change what comes next - learning steps, sibling burying).
Suspended, buried, deleted and merged-away cards never appear. Empty `items` with all
counts zero means done for the study day.

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

    try:
        # The next cards to review, built by the shared core's queue builder.
        api_response = api_instance.get_review_queue(limit=limit, deck_id=deck_id, kinds=kinds)
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
**200** | The queue prefix and today&#39;s remaining counts. |  -  |
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

