# kotobaya_client.DecksApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**add_deck_items**](DecksApi.md#add_deck_items) | **POST** /decks/{deck_id}/items | Add several items to a user deck.
[**create_deck**](DecksApi.md#create_deck) | **POST** /decks | Create a user or smart deck, or enable a generated list.
[**delete_deck**](DecksApi.md#delete_deck) | **DELETE** /decks/{deck_id} | Delete a user, smart or list deck. Notes and cards are untouched.
[**get_deck**](DecksApi.md#get_deck) | **GET** /decks/{deck_id} | One deck.
[**get_deck_coverage**](DecksApi.md#get_deck_coverage) | **GET** /decks/{deck_id}/coverage | Coverage of a deck&#39;s items by your word statuses.
[**list_deck_items**](DecksApi.md#list_deck_items) | **GET** /decks/{deck_id}/items | Items of a deck with dictionary summary and word status.
[**list_deck_lists**](DecksApi.md#list_deck_lists) | **GET** /deck-lists | Generated lists (JLPT, frequency bands) that can be enabled as &#x60;list&#x60; decks.
[**list_decks**](DecksApi.md#list_decks) | **GET** /decks | List decks.
[**put_deck_item**](DecksApi.md#put_deck_item) | **PUT** /decks/{deck_id}/items/{item_key} | Add one item to a user deck, or move it.
[**remove_deck_item**](DecksApi.md#remove_deck_item) | **DELETE** /decks/{deck_id}/items/{item_key} | Remove one item from a user deck (reversible by putting it again).
[**update_deck**](DecksApi.md#update_deck) | **PATCH** /decks/{deck_id} | Rename, reprioritise, enable/disable, set limits or settings.


# **add_deck_items**
> DeckItemList add_deck_items(deck_id, deck_items_add)

Add several items to a user deck.

Server emits one `deck.item_put` per element, in array order, in one transaction.
Each element names exactly one of `entry_seq` or `note_id`. A `note_id` whose note has
an `entry_seq` is stored in entry form (`e:<seq>`); a merge loser's id is resolved to the
survivor first. Elements without `position` are appended after the current maximum,
1024 apart, in array order. An element already present (live or removed) is put again
(the put clears a removal and moves it). Duplicates inside the array: the last wins.
1 to 1000 elements. Not a `user` deck: `409 deck_read_only`. Unknown entry or note: `422`
naming the element; nothing is applied.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.deck_item_list import DeckItemList
from kotobaya_client.models.deck_items_add import DeckItemsAdd
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
    api_instance = kotobaya_client.DecksApi(api_client)
    deck_id = '01931f40-2000-7000-8000-000000000d01' # str | Deck id. For a media deck this equals the media id.
    deck_items_add = {"items":[{"entry_seq":1358280},{"entry_seq":1002430,"position":2048},{"note_id":"01931f40-1a2b-7c3d-8e4f-000000000a02"}]} # DeckItemsAdd | 

    try:
        # Add several items to a user deck.
        api_response = api_instance.add_deck_items(deck_id, deck_items_add)
        print("The response of DecksApi->add_deck_items:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DecksApi->add_deck_items: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **deck_id** | **str**| Deck id. For a media deck this equals the media id. | 
 **deck_items_add** | [**DeckItemsAdd**](DeckItemsAdd.md)|  | 

### Return type

[**DeckItemList**](DeckItemList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The items as stored, in request order (after de-duplication). |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **create_deck**
> Deck create_deck(deck_create)

Create a user or smart deck, or enable a generated list.

Server emits `deck.created` (origin: server device, which is what makes `list` decks
legal here).

- `type: user` - `name` required. Starts empty.
- `type: smart` - `name` and `query` required; `query` must parse (`422 invalid_query`).
- `type: list` - `list_key` required and must be in `GET /deck-lists` (`422` otherwise);
  `name` defaults to the catalogue title. Under the op-apply lock: if a live deck with
  that `list_key` exists it is returned with `200` and nothing is emitted (a sent `id`
  is then ignored). Otherwise the deck is created and a job of kind `deck-build` is
  queued that builds the item snapshot and makes the server emit
  `deck.content_published`; until then the deck has `content_version = 0` and no items.
- `type: media` is `422`: media decks are created only by import jobs.

Properties that do not belong to the type (`query` on a user deck, ...) are `422`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.deck import Deck
from kotobaya_client.models.deck_create import DeckCreate
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
    api_instance = kotobaya_client.DecksApi(api_client)
    deck_create = {"list_key":"jlpt-n3","new_cards_per_day":5,"type":"list"} # DeckCreate | 

    try:
        # Create a user or smart deck, or enable a generated list.
        api_response = api_instance.create_deck(deck_create)
        print("The response of DecksApi->create_deck:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DecksApi->create_deck: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **deck_create** | [**DeckCreate**](DeckCreate.md)|  | 

### Return type

[**Deck**](Deck.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The deck already existed (same &#x60;id&#x60;, or same &#x60;list_key&#x60;). |  -  |
**201** | Created. |  * Kotobaya-Seq -  <br>  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **delete_deck**
> delete_deck(deck_id)

Delete a user, smart or list deck. Notes and cards are untouched.

Server emits `deck.deleted`. A `media` deck is `409 deck_read_only`: delete the media
(`DELETE /media/{media_id}`), which tombstones its deck. A deleted `list` deck can be
enabled again later (a new deck with a new id). Already deleted: `204`, no op.


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
    api_instance = kotobaya_client.DecksApi(api_client)
    deck_id = '01931f40-2000-7000-8000-000000000d01' # str | Deck id. For a media deck this equals the media id.

    try:
        # Delete a user, smart or list deck. Notes and cards are untouched.
        api_instance.delete_deck(deck_id)
    except Exception as e:
        print("Exception when calling DecksApi->delete_deck: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **deck_id** | **str**| Deck id. For a media deck this equals the media id. | 

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
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_deck**
> Deck get_deck(deck_id, include_deleted=include_deleted)

One deck.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.deck import Deck
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
    api_instance = kotobaya_client.DecksApi(api_client)
    deck_id = '01931f40-2000-7000-8000-000000000d01' # str | Deck id. For a media deck this equals the media id.
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)

    try:
        # One deck.
        api_response = api_instance.get_deck(deck_id, include_deleted=include_deleted)
        print("The response of DecksApi->get_deck:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DecksApi->get_deck: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **deck_id** | **str**| Deck id. For a media deck this equals the media id. | 
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]

### Return type

[**Deck**](Deck.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The deck. |  -  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_deck_coverage**
> Coverage get_deck_coverage(deck_id, hide=hide)

Coverage of a deck's items by your word statuses.

Computed at request time from live `deck_item` rows that have an `entry_seq` and
`word_status`. For `media` decks the result equals `GET /media/{id}/coverage` without
`segment_id` (including `segments`, `curve` and token numbers). For `user` and `list`
decks token numbers count each item once (`occurrence_count` is 0, treated as 1),
`segments` is empty and `difficulty` is null. Smart decks: `409 not_applicable`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.coverage import Coverage
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
    api_instance = kotobaya_client.DecksApi(api_client)
    deck_id = '01931f40-2000-7000-8000-000000000d01' # str | Deck id. For a media deck this equals the media id.
    hide = [ignored] # List[str] | Classes left out of every coverage number, comma-separated. `names`, `particles`, `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`; `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide nothing.  (optional) (default to [ignored])

    try:
        # Coverage of a deck's items by your word statuses.
        api_response = api_instance.get_deck_coverage(deck_id, hide=hide)
        print("The response of DecksApi->get_deck_coverage:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DecksApi->get_deck_coverage: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **deck_id** | **str**| Deck id. For a media deck this equals the media id. | 
 **hide** | [**List[str]**](str.md)| Classes left out of every coverage number, comma-separated. &#x60;names&#x60;, &#x60;particles&#x60;, &#x60;auxiliary&#x60; &#x3D; entries whose &#x60;occurrence.pos_class&#x60; is &#x60;name&#x60;, &#x60;particle&#x60;, &#x60;auxiliary&#x60;; &#x60;ignored&#x60; &#x3D; entries whose word status is &#x60;blacklisted&#x60; or &#x60;ignored&#x60;; &#x60;unmapped&#x60; &#x3D; tokens that matched no dictionary entry. Default &#x60;ignored&#x60;. Pass an empty value (&#x60;hide&#x3D;&#x60;) to hide nothing.  | [optional] [default to [ignored]]

### Return type

[**Coverage**](Coverage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Coverage numbers. |  -  |
**404** | No such resource. |  -  |
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_deck_items**
> DeckItemPage list_deck_items(deck_id, status=status, sort=sort, limit=limit, cursor=cursor)

Items of a deck with dictionary summary and word status.

Live items only. For a media deck this is the media's vocabulary list: `status=new` with
`sort=occurrence_count` gives "unknown words by in-deck frequency, global rank beside
them". An item whose `note_id` is a merge loser is reported with the survivor's id.
Items whose entry is missing from the dictionary have `entry: null`. Smart decks have no
items: `409 not_applicable` (use `GET /cards?query=`). Deleted deck: `410 gone`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.deck_item_page import DeckItemPage
from kotobaya_client.models.word_status_value import WordStatusValue
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
    api_instance = kotobaya_client.DecksApi(api_client)
    deck_id = '01931f40-2000-7000-8000-000000000d01' # str | Deck id. For a media deck this equals the media id.
    status = [kotobaya_client.WordStatusValue()] # List[WordStatusValue] | Only items whose effective word status is one of these (comma-separated). Items without an `entry_seq` have no status and match only when this parameter is absent. (optional)
    sort = 'occurrence_count' # str | `position` = the deck's new-card order (ties by `item_key`); `occurrence_count` = most frequent in this deck first; `freq_rank` = globally most frequent first (NULLs last). Ties by `item_key`. (optional)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # Items of a deck with dictionary summary and word status.
        api_response = api_instance.list_deck_items(deck_id, status=status, sort=sort, limit=limit, cursor=cursor)
        print("The response of DecksApi->list_deck_items:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DecksApi->list_deck_items: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **deck_id** | **str**| Deck id. For a media deck this equals the media id. | 
 **status** | [**List[WordStatusValue]**](WordStatusValue.md)| Only items whose effective word status is one of these (comma-separated). Items without an &#x60;entry_seq&#x60; have no status and match only when this parameter is absent. | [optional] 
 **sort** | **str**| &#x60;position&#x60; &#x3D; the deck&#39;s new-card order (ties by &#x60;item_key&#x60;); &#x60;occurrence_count&#x60; &#x3D; most frequent in this deck first; &#x60;freq_rank&#x60; &#x3D; globally most frequent first (NULLs last). Ties by &#x60;item_key&#x60;. | [optional] 
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**DeckItemPage**](DeckItemPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of deck items. |  -  |
**404** | No such resource. |  -  |
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_deck_lists**
> DeckListCatalogue list_deck_lists()

Generated lists (JLPT, frequency bands) that can be enabled as `list` decks.

The catalogue is computed by the server from the current dictionary release: `jlpt-n5` ...
`jlpt-n1` and, per loaded frequency list, `freq-<list id>-<N>` for N in 1000, 2000, 5000,
10000, 20000. `deck_id` is set when a live deck for the key exists. Not paginated;
ordered by `list_key`. Empty when no dictionary release is loaded.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.deck_list_catalogue import DeckListCatalogue
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
    api_instance = kotobaya_client.DecksApi(api_client)

    try:
        # Generated lists (JLPT, frequency bands) that can be enabled as `list` decks.
        api_response = api_instance.list_deck_lists()
        print("The response of DecksApi->list_deck_lists:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DecksApi->list_deck_lists: %s\n" % e)
```



### Parameters

This endpoint does not need any parameter.

### Return type

[**DeckListCatalogue**](DeckListCatalogue.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Available lists. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_decks**
> DeckList list_decks(type=type, include_deleted=include_deleted)

List decks.

Not paginated. Ordered by `(priority, id)` ascending - the order new cards are drawn in.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.deck_list import DeckList
from kotobaya_client.models.deck_type import DeckType
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
    api_instance = kotobaya_client.DecksApi(api_client)
    type = kotobaya_client.DeckType() # DeckType | Only decks of this type. (optional)
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)

    try:
        # List decks.
        api_response = api_instance.list_decks(type=type, include_deleted=include_deleted)
        print("The response of DecksApi->list_decks:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DecksApi->list_decks: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **type** | [**DeckType**](.md)| Only decks of this type. | [optional] 
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]

### Return type

[**DeckList**](DeckList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Decks. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **put_deck_item**
> DeckItem put_deck_item(deck_id, item_key, deck_item_put=deck_item_put)

Add one item to a user deck, or move it.

Server emits `deck.item_put`. Without a body, or without `position`: a new item is
appended (maximum position + 1024); an existing live item keeps its position.
An `n:<id>` key whose note has an `entry_seq` is `422` (use the `e:` form); the note or
entry must exist. Not a `user` deck: `409 deck_read_only`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.deck_item import DeckItem
from kotobaya_client.models.deck_item_put import DeckItemPut
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
    api_instance = kotobaya_client.DecksApi(api_client)
    deck_id = '01931f40-2000-7000-8000-000000000d01' # str | Deck id. For a media deck this equals the media id.
    item_key = 'e:1358280' # str | `e:<entry_seq>` or `n:<note uuid, lowercase>`. The colon need not be percent-encoded.
    deck_item_put = {} # DeckItemPut |  (optional)

    try:
        # Add one item to a user deck, or move it.
        api_response = api_instance.put_deck_item(deck_id, item_key, deck_item_put=deck_item_put)
        print("The response of DecksApi->put_deck_item:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DecksApi->put_deck_item: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **deck_id** | **str**| Deck id. For a media deck this equals the media id. | 
 **item_key** | **str**| &#x60;e:&lt;entry_seq&gt;&#x60; or &#x60;n:&lt;note uuid, lowercase&gt;&#x60;. The colon need not be percent-encoded. | 
 **deck_item_put** | [**DeckItemPut**](DeckItemPut.md)|  | [optional] 

### Return type

[**DeckItem**](DeckItem.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The item as stored. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **remove_deck_item**
> remove_deck_item(deck_id, item_key)

Remove one item from a user deck (reversible by putting it again).

Server emits `deck.item_removed`. Item absent or already removed: `204`, no op.
Not a `user` deck: `409 deck_read_only`.


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
    api_instance = kotobaya_client.DecksApi(api_client)
    deck_id = '01931f40-2000-7000-8000-000000000d01' # str | Deck id. For a media deck this equals the media id.
    item_key = 'e:1358280' # str | `e:<entry_seq>` or `n:<note uuid, lowercase>`. The colon need not be percent-encoded.

    try:
        # Remove one item from a user deck (reversible by putting it again).
        api_instance.remove_deck_item(deck_id, item_key)
    except Exception as e:
        print("Exception when calling DecksApi->remove_deck_item: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **deck_id** | **str**| Deck id. For a media deck this equals the media id. | 
 **item_key** | **str**| &#x60;e:&lt;entry_seq&gt;&#x60; or &#x60;n:&lt;note uuid, lowercase&gt;&#x60;. The colon need not be percent-encoded. | 

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
**204** | Removed. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **update_deck**
> Deck update_deck(deck_id, deck_patch)

Rename, reprioritise, enable/disable, set limits or settings.

Server emits one `deck.updated` with the registers present: `name`, `priority`,
`enabled`, `new_cards_per_day`, `reviews_per_day`, `query` (smart decks only, must
parse), and one `settings.<key>` per key of `settings` (partial map; a `null` value
removes the key). Works for every deck type. `type`, `media_id`, `list_key` are
immutable (`422`). An empty body is `422`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.deck import Deck
from kotobaya_client.models.deck_patch import DeckPatch
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
    api_instance = kotobaya_client.DecksApi(api_client)
    deck_id = '01931f40-2000-7000-8000-000000000d01' # str | Deck id. For a media deck this equals the media id.
    deck_patch = {"enabled":false} # DeckPatch | 

    try:
        # Rename, reprioritise, enable/disable, set limits or settings.
        api_response = api_instance.update_deck(deck_id, deck_patch)
        print("The response of DecksApi->update_deck:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DecksApi->update_deck: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **deck_id** | **str**| Deck id. For a media deck this equals the media id. | 
 **deck_patch** | [**DeckPatch**](DeckPatch.md)|  | 

### Return type

[**Deck**](Deck.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The updated deck. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

