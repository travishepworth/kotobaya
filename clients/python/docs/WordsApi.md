# kotobaya_client.WordsApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**clear_word_status**](WordsApi.md#clear_word_status) | **DELETE** /words/{seq}/status | Clear the override; the status falls back to the derived one.
[**get_word_status**](WordsApi.md#get_word_status) | **GET** /words/{seq}/status | Word status of one entry.
[**list_confusables**](WordsApi.md#list_confusables) | **GET** /confusables | All confusable pairs (any note type).
[**list_hot_kanji**](WordsApi.md#list_hot_kanji) | **GET** /kanji/hot | Hot kanji - recent failures rolled up by kanji.
[**list_hot_words**](WordsApi.md#list_hot_words) | **GET** /words/hot | Hot words - recent struggle, not lifetime.
[**list_leeches**](WordsApi.md#list_leeches) | **GET** /words/leeches | Leech cards (lapses at or above &#x60;scheduler.leech_threshold&#x60;).
[**list_word_confusables**](WordsApi.md#list_word_confusables) | **GET** /words/{seq}/confusables | Confusable partners of the word&#39;s note.
[**set_confusable**](WordsApi.md#set_confusable) | **PUT** /confusables/{note_a_id}/{note_b_id}/{reason} | Confirm or reject a suggested pair, or record a pair yourself.
[**set_word_status**](WordsApi.md#set_word_status) | **PUT** /words/{seq}/status | Set an explicit status override (known, never-forget, blacklisted, ignored).


# **clear_word_status**
> WordStatus clear_word_status(seq)

Clear the override; the status falls back to the derived one.

Server emits `word.status_cleared`. No override present (or no row): `200` with the
current status, no op.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.word_status import WordStatus
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
    api_instance = kotobaya_client.WordsApi(api_client)
    seq = 1358280 # int | JMdict sequence number of the entry.

    try:
        # Clear the override; the status falls back to the derived one.
        api_response = api_instance.clear_word_status(seq)
        print("The response of WordsApi->clear_word_status:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling WordsApi->clear_word_status: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **seq** | **int**| JMdict sequence number of the entry. | 

### Return type

[**WordStatus**](WordStatus.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The word status after the change. |  * Kotobaya-Seq -  <br>  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_word_status**
> WordStatus get_word_status(seq)

Word status of one entry.

An entry without a `word_status` row is reported as `status = new` with every other property null. The entry need not exist in the dictionary.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.word_status import WordStatus
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
    api_instance = kotobaya_client.WordsApi(api_client)
    seq = 1358280 # int | JMdict sequence number of the entry.

    try:
        # Word status of one entry.
        api_response = api_instance.get_word_status(seq)
        print("The response of WordsApi->get_word_status:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling WordsApi->get_word_status: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **seq** | **int**| JMdict sequence number of the entry. | 

### Return type

[**WordStatus**](WordStatus.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The word status. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_confusables**
> ConfusablePage list_confusables(status=status, note_id=note_id, limit=limit, cursor=cursor)

All confusable pairs (any note type).

Ordered by `updated_at` descending, then `(note_a_id, note_b_id, reason)`. Pairs with a deleted note are hidden; merged notes are resolved to survivors.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.confusable_page import ConfusablePage
from kotobaya_client.models.confusable_status import ConfusableStatus
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
    api_instance = kotobaya_client.WordsApi(api_client)
    status = kotobaya_client.ConfusableStatus() # ConfusableStatus | Only pairs with this status. Default - all three. (optional)
    note_id = '01931f40-1a2b-7c3d-8e4f-000000000a01' # str | Only pairs involving this note. (optional)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # All confusable pairs (any note type).
        api_response = api_instance.list_confusables(status=status, note_id=note_id, limit=limit, cursor=cursor)
        print("The response of WordsApi->list_confusables:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling WordsApi->list_confusables: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **status** | [**ConfusableStatus**](.md)| Only pairs with this status. Default - all three. | [optional] 
 **note_id** | **str**| Only pairs involving this note. | [optional] 
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**ConfusablePage**](ConfusablePage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of pairs. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_hot_kanji**
> HotKanjiList list_hot_kanji(limit=limit)

Hot kanji - recent failures rolled up by kanji.

Scores come from the shared core (hot-word score summed over every live vocab note whose
entry contains the kanji, via `ref.entry_kanji`). Highest score first, then `literal`.
Only kanji with a score above zero. Not paginated.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.hot_kanji_list import HotKanjiList
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
    api_instance = kotobaya_client.WordsApi(api_client)
    limit = 20 # int | Maximum number of kanji. (optional) (default to 20)

    try:
        # Hot kanji - recent failures rolled up by kanji.
        api_response = api_instance.list_hot_kanji(limit=limit)
        print("The response of WordsApi->list_hot_kanji:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling WordsApi->list_hot_kanji: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **limit** | **int**| Maximum number of kanji. | [optional] [default to 20]

### Return type

[**HotKanjiList**](HotKanjiList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Hot kanji. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_hot_words**
> HotWordList list_hot_words(limit=limit)

Hot words - recent struggle, not lifetime.

Score from the shared core (failures over the last 14 days with decay, FSRS difficulty,
lapse rate, relearning failures). Only live vocab notes with a score above zero; highest
score first, then note id. Not paginated.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.hot_word_list import HotWordList
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
    api_instance = kotobaya_client.WordsApi(api_client)
    limit = 20 # int | Maximum number of words. (optional) (default to 20)

    try:
        # Hot words - recent struggle, not lifetime.
        api_response = api_instance.list_hot_words(limit=limit)
        print("The response of WordsApi->list_hot_words:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling WordsApi->list_hot_words: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **limit** | **int**| Maximum number of words. | [optional] [default to 20]

### Return type

[**HotWordList**](HotWordList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Hot words. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_leeches**
> CardPage list_leeches(limit=limit, cursor=cursor)

Leech cards (lapses at or above `scheduler.leech_threshold`).

Live cards of live notes with `lapses >= scheduler.leech_threshold`, suspended ones
included. Ordered by `lapses` descending, then card id. Covers every note type, not only
vocab. Equivalent to `GET /cards?query=tag:leech&sort=-lapses`.


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
    api_instance = kotobaya_client.WordsApi(api_client)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # Leech cards (lapses at or above `scheduler.leech_threshold`).
        api_response = api_instance.list_leeches(limit=limit, cursor=cursor)
        print("The response of WordsApi->list_leeches:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling WordsApi->list_leeches: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
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
**200** | One page of leech cards. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_word_confusables**
> ConfusableList list_word_confusables(seq, status=status)

Confusable partners of the word's note.

Pairs involving the live vocab note of this entry, resolved through `merged_into_id`,
pairs with a deleted note hidden. Ordered: `confirmed` first, then `suggested`; inside by
`updated_at` descending. `rejected` pairs only with `status=rejected`. No note for the
entry: empty list. Not paginated.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.confusable_list import ConfusableList
from kotobaya_client.models.confusable_status import ConfusableStatus
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
    api_instance = kotobaya_client.WordsApi(api_client)
    seq = 1358280 # int | JMdict sequence number of the entry.
    status = kotobaya_client.ConfusableStatus() # ConfusableStatus | Only pairs with this status. Default - `suggested` and `confirmed`. (optional)

    try:
        # Confusable partners of the word's note.
        api_response = api_instance.list_word_confusables(seq, status=status)
        print("The response of WordsApi->list_word_confusables:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling WordsApi->list_word_confusables: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **seq** | **int**| JMdict sequence number of the entry. | 
 **status** | [**ConfusableStatus**](.md)| Only pairs with this status. Default - &#x60;suggested&#x60; and &#x60;confirmed&#x60;. | [optional] 

### Return type

[**ConfusableList**](ConfusableList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Confusable pairs. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **set_confusable**
> Confusable set_confusable(note_a_id, note_b_id, reason, confusable_set)

Confirm or reject a suggested pair, or record a pair yourself.

Server emits `confusable.confirmed` or `confusable.rejected`. If the pair does not exist
yet the op creates it (a hand-made pair is simply a confirmed one). Both notes must
exist; merge losers are resolved to survivors first, and if both resolve to the same
note the call is `422`. A user decision always beats a suggestion; between decisions the
later clock wins. Rejected pairs are kept so they are not suggested again.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.confusable import Confusable
from kotobaya_client.models.confusable_reason import ConfusableReason
from kotobaya_client.models.confusable_set import ConfusableSet
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
    api_instance = kotobaya_client.WordsApi(api_client)
    note_a_id = '01931f40-1a2b-7c3d-8e4f-000000000a01' # str | One note of the pair. The two ids may be given in either order; the server stores the smaller first.
    note_b_id = '01931f40-1a2b-7c3d-8e4f-000000000a02' # str | The other note. Must differ from `note_a_id` (`422`).
    reason = kotobaya_client.ConfusableReason() # ConfusableReason | Why the two are confusable.
    confusable_set = {"status":"confirmed"} # ConfusableSet | 

    try:
        # Confirm or reject a suggested pair, or record a pair yourself.
        api_response = api_instance.set_confusable(note_a_id, note_b_id, reason, confusable_set)
        print("The response of WordsApi->set_confusable:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling WordsApi->set_confusable: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **note_a_id** | **str**| One note of the pair. The two ids may be given in either order; the server stores the smaller first. | 
 **note_b_id** | **str**| The other note. Must differ from &#x60;note_a_id&#x60; (&#x60;422&#x60;). | 
 **reason** | [**ConfusableReason**](.md)| Why the two are confusable. | 
 **confusable_set** | [**ConfusableSet**](ConfusableSet.md)|  | 

### Return type

[**Confusable**](Confusable.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The pair as stored. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **set_word_status**
> WordStatus set_word_status(seq, word_status_set)

Set an explicit status override (known, never-forget, blacklisted, ignored).

Server emits `word.status_set` with payload `origin = manual` (stored as `override_origin`). `new` and `learning`
cannot be set (they are derived): to return to the derived status use `DELETE`.
The entry need not exist in the dictionary and need not have a note. Setting the status
it already has still emits (bumps the clock).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.word_status import WordStatus
from kotobaya_client.models.word_status_set import WordStatusSet
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
    api_instance = kotobaya_client.WordsApi(api_client)
    seq = 1358280 # int | JMdict sequence number of the entry.
    word_status_set = {"status":"blacklisted"} # WordStatusSet | 

    try:
        # Set an explicit status override (known, never-forget, blacklisted, ignored).
        api_response = api_instance.set_word_status(seq, word_status_set)
        print("The response of WordsApi->set_word_status:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling WordsApi->set_word_status: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **seq** | **int**| JMdict sequence number of the entry. | 
 **word_status_set** | [**WordStatusSet**](WordStatusSet.md)|  | 

### Return type

[**WordStatus**](WordStatus.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The word status after the change. |  * Kotobaya-Seq -  <br>  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

