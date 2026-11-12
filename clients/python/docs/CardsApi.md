# kotobaya_client.CardsApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**bulk_cards**](CardsApi.md#bulk_cards) | **POST** /cards/bulk | Apply one action to many cards (suspend, tag, move, delete, ...).
[**bulk_schedule_cards**](CardsApi.md#bulk_schedule_cards) | **POST** /cards/bulk/schedule | Reschedule or reset many cards (manual review-log rows).
[**create_card**](CardsApi.md#create_card) | **POST** /cards | Add an optional card kind to an existing note.
[**delete_card**](CardsApi.md#delete_card) | **DELETE** /cards/{card_id} | Delete one card (permanent tombstone); the note and its other cards stay.
[**get_card**](CardsApi.md#get_card) | **GET** /cards/{card_id} | One card with its note summary.
[**list_card_reviews**](CardsApi.md#list_card_reviews) | **GET** /cards/{card_id}/reviews | Review history of a card (its whole replay set plus drills and crams).
[**list_cards**](CardsApi.md#list_cards) | **GET** /cards | Find cards with the card search language.
[**update_card**](CardsApi.md#update_card) | **PATCH** /cards/{card_id} | Suspend, bury, flag or re-preset one card.


# **bulk_cards**
> CardBulkResult bulk_cards(card_bulk_request)

Apply one action to many cards (suspend, tag, move, delete, ...).

Select cards with exactly one of `card_ids` (1 to 5000 ids) or `query` (card search
string). Unknown, deleted and merged-away ids in `card_ids` are skipped and counted in
`skipped` (merge losers are **not** redirected in bulk). A selector matching more than
5000 cards is `422 bulk_too_large`; nothing is applied. Matching zero cards is a
successful no-op. All ops of one call are applied in one transaction, ordered by card id.

| `action.type` | Required params | Server emits, per matched card unless noted |
|---|---|---|
| `suspend` | - | `card.suspended` |
| `unsuspend` | - | `card.unsuspended` |
| `bury` | `until` (optional; default = start of the next study day) | `card.buried` |
| `unbury` | - | `card.unburied` |
| `flag` | `flags` (0-7; 0 clears) | `card.flag_set` |
| `set_preset` | `preset_id` (live preset) | `card.preset_set` |
| `add_tags`, `remove_tags` | `tags` (non-empty) | one `note.updated` (register `tags`) per **distinct note** of the matched cards |
| `move` | `to_deck_id` (live `user` deck), optional `from_deck_id` (live `user` deck) | per distinct note: `deck.item_put` into `to_deck_id` (key `e:<seq>` or `n:<note id>`, appended in card-id order at max position + 1024 each) and, with `from_deck_id`, `deck.item_removed` there if the item exists |
| `delete` | - | `card.deleted` |

Cards already in the target state still get an op (it bumps the clock) except: `delete`
on a deleted card, and tag actions that would not change a note's tag set - those are
counted in `skipped`. An `add_tags` that would give any matched note more than 200 tags
is `422 validation_failed` (error `out_of_range` at `/action/tags`); nothing is applied
(C-2). Params not listed for the action type are `422`.
A `to_deck_id`/`from_deck_id` that is not a live `user` deck is `409 deck_read_only`.
With `dry_run: true` nothing is emitted and the counts say what would happen.
Repeating a call is safe: register actions converge.

Reschedule and reset write review history (`review.logged`) and therefore need the
`review` scope; they are the separate operation `POST /cards/bulk/schedule`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.card_bulk_request import CardBulkRequest
from kotobaya_client.models.card_bulk_result import CardBulkResult
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
    api_instance = kotobaya_client.CardsApi(api_client)
    card_bulk_request = {"action":{"from_deck_id":"01931f40-2000-7000-8000-000000000d02","to_deck_id":"01931f40-2000-7000-8000-000000000d01","type":"move"},"query":"deck:\"Inbox\""} # CardBulkRequest | 

    try:
        # Apply one action to many cards (suspend, tag, move, delete, ...).
        api_response = api_instance.bulk_cards(card_bulk_request)
        print("The response of CardsApi->bulk_cards:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling CardsApi->bulk_cards: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **card_bulk_request** | [**CardBulkRequest**](CardBulkRequest.md)|  | 

### Return type

[**CardBulkResult**](CardBulkResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | What was (or with &#x60;dry_run&#x60; would be) done. |  * Kotobaya-Seq -  <br>  |
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **bulk_schedule_cards**
> CardBulkResult bulk_schedule_cards(card_bulk_schedule_request)

Reschedule or reset many cards (manual review-log rows).

Same selector, limits, ordering, transaction and `dry_run` rules as `POST /cards/bulk`.
Needs the `review` scope, like every other way of writing `review_log` (`POST /sync/push`
needs `review` for `review.*` ops; `POST /review/answer`).

| `action.type` | Required params | Server emits, per matched card |
|---|---|---|
| `reschedule` | `due_at` | `review.logged` with `review_type = manual`, `manual_action = set_due`, `manual_due_at = due_at` |
| `reset` | - | `review.logged` with `review_type = manual`, `manual_action = reset` |

- `reset` of a card in state `new` is skipped (counted in `skipped`).
- `reschedule` is `422` for the whole call if any matched card is `new` (a new card has no
  memory state to keep); nothing is applied.
- Suspended cards are processed (the manual row changes the schedule cache; the card
  stays suspended).
- Repeating a call adds another manual row per card with the same effect on the
  schedule (manual rows are not deduplicated). Each manual row can be undone with
  `POST /review/undo` by its id (`GET /cards/{card_id}/reviews`).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.card_bulk_result import CardBulkResult
from kotobaya_client.models.card_bulk_schedule_request import CardBulkScheduleRequest
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
    api_instance = kotobaya_client.CardsApi(api_client)
    card_bulk_schedule_request = {"action":{"due_at":"2026-11-20T19:00:00.000Z","type":"reschedule"},"dry_run":true,"query":"is:due kind:reading"} # CardBulkScheduleRequest | 

    try:
        # Reschedule or reset many cards (manual review-log rows).
        api_response = api_instance.bulk_schedule_cards(card_bulk_schedule_request)
        print("The response of CardsApi->bulk_schedule_cards:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling CardsApi->bulk_schedule_cards: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **card_bulk_schedule_request** | [**CardBulkScheduleRequest**](CardBulkScheduleRequest.md)|  | 

### Return type

[**CardBulkResult**](CardBulkResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | What was (or with &#x60;dry_run&#x60; would be) done. |  * Kotobaya-Seq -  <br>  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **create_card**
> Card create_card(card_create)

Add an optional card kind to an existing note.

Server emits `card.created`. `kind` must be a key of the note type's `templates`.
If the note already has a live card of that kind it is returned with `200` and nothing
is emitted (REST never creates a duplicate `(note, kind)`). A merge loser's `note_id`
acts on the survivor. A deleted note is `410 gone`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.card import Card
from kotobaya_client.models.card_create import CardCreate
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
    api_instance = kotobaya_client.CardsApi(api_client)
    card_create = {"kind":"listening","note_id":"01931f40-1a2b-7c3d-8e4f-000000000a01"} # CardCreate | 

    try:
        # Add an optional card kind to an existing note.
        api_response = api_instance.create_card(card_create)
        print("The response of CardsApi->create_card:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling CardsApi->create_card: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **card_create** | [**CardCreate**](CardCreate.md)|  | 

### Return type

[**Card**](Card.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The card already existed (same &#x60;id&#x60;, or same note and kind). |  -  |
**201** | Created. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **delete_card**
> delete_card(card_id)

Delete one card (permanent tombstone); the note and its other cards stay.

Server emits `card.deleted`. Already deleted or a merge loser - `204`, no op.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
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
    api_instance = kotobaya_client.CardsApi(api_client)
    card_id = '01931f40-1a2b-7c3d-8e4f-000000000c01' # str | Card id.

    try:
        # Delete one card (permanent tombstone); the note and its other cards stay.
        api_instance.delete_card(card_id)
    except Exception as e:
        print("Exception when calling CardsApi->delete_card: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **card_id** | **str**| Card id. | 

### Return type

void (empty response body)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**204** | Deleted. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_card**
> Card get_card(card_id, include_deleted=include_deleted)

One card with its note summary.

A merge loser's id returns the survivor (`Content-Location` set). Deleted card, or card of a deleted note - `410 gone` unless `include_deleted=true`.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.card import Card
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
    api_instance = kotobaya_client.CardsApi(api_client)
    card_id = '01931f40-1a2b-7c3d-8e4f-000000000c01' # str | Card id.
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)

    try:
        # One card with its note summary.
        api_response = api_instance.get_card(card_id, include_deleted=include_deleted)
        print("The response of CardsApi->get_card:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling CardsApi->get_card: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **card_id** | **str**| Card id. | 
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]

### Return type

[**Card**](Card.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The card (or its merge survivor). |  * Content-Location -  <br>  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_card_reviews**
> ReviewPage list_card_reviews(card_id, limit=limit, cursor=cursor)

Review history of a card (its whole replay set plus drills and crams).

Rows of `review_log` whose `card_id` is this card or any card merged into it, every
`review_type`, newest first by `(reviewed_at, hlc, id)` descending - the reverse of the
replay order (SYNC.md 9.1), so a late or re-minted review (SYNC.md 10.4) sits where it was
answered, not where it was logged. Undone rows are included
(`undone_at` set) so the history is complete. A merge loser's id resolves to the
survivor. Works for deleted cards (history is never deleted).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.review_page import ReviewPage
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
    api_instance = kotobaya_client.CardsApi(api_client)
    card_id = '01931f40-1a2b-7c3d-8e4f-000000000c01' # str | Card id.
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # Review history of a card (its whole replay set plus drills and crams).
        api_response = api_instance.list_card_reviews(card_id, limit=limit, cursor=cursor)
        print("The response of CardsApi->list_card_reviews:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling CardsApi->list_card_reviews: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **card_id** | **str**| Card id. | 
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**ReviewPage**](ReviewPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of review log rows. |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_cards**
> CardPage list_cards(query=query, sort=sort, limit=limit, cursor=cursor)

Find cards with the card search language.

`query` is a string in the card search language, parsed by the shared core
(e.g. `deck:"Frieren" is:due tag:leech lapses>3 kanji:績 rated:7:1 kind:reading`).
Absent or empty = all live cards. A string that does not parse is `422 invalid_query`
with the UTF-16 offset of the error in `errors[0].position`. Cards of deleted notes,
deleted cards and merge losers never match. Each card embeds a `note` summary.
`is:due` and similar relative terms are evaluated at request time for the current study
day.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.card_page import CardPage
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
    api_instance = kotobaya_client.CardsApi(api_client)
    query = 'deck:\"Frieren\" is:due lapses>3' # str | Card search string (grammar owned by the shared core). Maximum 2000 characters. (optional)
    sort = 'due_at' # str | Sort key; a leading `-` means descending. Ties are broken by card `id` ascending. NULL values sort last in either direction. (optional)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # Find cards with the card search language.
        api_response = api_instance.list_cards(query=query, sort=sort, limit=limit, cursor=cursor)
        print("The response of CardsApi->list_cards:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling CardsApi->list_cards: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **query** | **str**| Card search string (grammar owned by the shared core). Maximum 2000 characters. | [optional] 
 **sort** | **str**| Sort key; a leading &#x60;-&#x60; means descending. Ties are broken by card &#x60;id&#x60; ascending. NULL values sort last in either direction. | [optional] 
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**CardPage**](CardPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of cards. |  -  |
**422** | The card search string does not parse (or another validation error; see &#x60;code&#x60;). |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **update_card**
> Card update_card(card_id, card_patch)

Suspend, bury, flag or re-preset one card.

Server emits one op per property present, in this order: `suspended` →
`card.suspended` (true) or `card.unsuspended` (false); `buried_until` → `card.buried`
(an instant) or `card.unburied` (`null`); `flags` → `card.flag_set`; `preset_id` →
`card.preset_set` (must be a live preset). An empty body is `422`. A merge loser's id
acts on the survivor. Schedule properties cannot be patched (use `POST /cards/bulk/schedule`
`reschedule`/`reset` or review the card).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.card import Card
from kotobaya_client.models.card_patch import CardPatch
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
    api_instance = kotobaya_client.CardsApi(api_client)
    card_id = '01931f40-1a2b-7c3d-8e4f-000000000c01' # str | Card id.
    card_patch = {"buried_until":"2026-11-13T19:00:00.000Z"} # CardPatch | 

    try:
        # Suspend, bury, flag or re-preset one card.
        api_response = api_instance.update_card(card_id, card_patch)
        print("The response of CardsApi->update_card:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling CardsApi->update_card: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **card_id** | **str**| Card id. | 
 **card_patch** | [**CardPatch**](CardPatch.md)|  | 

### Return type

[**Card**](Card.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The updated card. |  * Content-Location -  <br>  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

