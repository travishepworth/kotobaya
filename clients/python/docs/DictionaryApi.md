# kotobaya_client.DictionaryApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**get_dict_entry**](DictionaryApi.md#get_dict_entry) | **GET** /dict/entries/{seq} | One JMdict entry with frequency, pitch, kanji and your status.
[**get_dict_release**](DictionaryApi.md#get_dict_release) | **GET** /dict/release | The current dictionary release and its downloadable device builds.
[**get_kanji**](DictionaryApi.md#get_kanji) | **GET** /kanji/{literal} | One kanji from KANJIDIC2 with components and your kanji note, if any.
[**list_entry_examples**](DictionaryApi.md#list_entry_examples) | **GET** /dict/entries/{seq}/examples | Example sentences for an entry, from your media and from Tatoeba.
[**list_kanji_words**](DictionaryApi.md#list_kanji_words) | **GET** /kanji/{literal}/words | Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17).
[**refresh_dictionary**](DictionaryApi.md#refresh_dictionary) | **POST** /dict/refresh | Queue a dictionary refresh (download sources, build and load a new release).
[**search_dictionary**](DictionaryApi.md#search_dictionary) | **GET** /dict/search | Search JMdict entries by kanji, kana, romaji or English.


# **get_dict_entry**
> DictEntry get_dict_entry(seq)

One JMdict entry with frequency, pitch, kanji and your status.

Retired entries are still returned (`retired` is true). Unknown `seq` is `404`.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.dict_entry import DictEntry
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
    api_instance = kotobaya_client.DictionaryApi(api_client)
    seq = 1358280 # int | JMdict sequence number of the entry.

    try:
        # One JMdict entry with frequency, pitch, kanji and your status.
        api_response = api_instance.get_dict_entry(seq)
        print("The response of DictionaryApi->get_dict_entry:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DictionaryApi->get_dict_entry: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **seq** | **int**| JMdict sequence number of the entry. | 

### Return type

[**DictEntry**](DictEntry.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The entry. |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_dict_release**
> DictRelease get_dict_release()

The current dictionary release and its downloadable device builds.

The phone compares `id` with the release of its dictionary file and, when behind,
downloads an artifact through `GET /blobs/{hash}`: a `diff` whose `from_release` equals
its release and whose `scope` matches, else the `full` build. `404` when no release has
been loaded yet. Package and diff formats, hashes and the full install/update algorithm:
`contracts/db/DICT_PACKAGE.md`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.dict_release import DictRelease
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
    api_instance = kotobaya_client.DictionaryApi(api_client)

    try:
        # The current dictionary release and its downloadable device builds.
        api_response = api_instance.get_dict_release()
        print("The response of DictionaryApi->get_dict_release:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DictionaryApi->get_dict_release: %s\n" % e)
```



### Parameters

This endpoint does not need any parameter.

### Return type

[**DictRelease**](DictRelease.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Current release. |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_kanji**
> Kanji get_kanji(literal)

One kanji from KANJIDIC2 with components and your kanji note, if any.

More than one code point is `422`; a character not in `ref.kanji` is `404`.
(`maxLength: 2` because a code point outside the BMP is two UTF-16 units.)


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.kanji import Kanji
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
    api_instance = kotobaya_client.DictionaryApi(api_client)
    literal = '績' # str | Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment `hot` is the separate operation `listHotKanji`.

    try:
        # One kanji from KANJIDIC2 with components and your kanji note, if any.
        api_response = api_instance.get_kanji(literal)
        print("The response of DictionaryApi->get_kanji:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DictionaryApi->get_kanji: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **literal** | **str**| Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment &#x60;hot&#x60; is the separate operation &#x60;listHotKanji&#x60;. | 

### Return type

[**Kanji**](Kanji.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The kanji. |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_entry_examples**
> EntryExamples list_entry_examples(seq, limit=limit)

Example sentences for an entry, from your media and from Tatoeba.

`media`: up to `limit` sentences from imported media that contain the entry (resolved
through `occurrence.sentence_ids`), sentences with the fewest unknown words first, then
by media `created_at` descending, then sentence id. `tatoeba`: up to `limit` rows of
`ref.example_sentence` linked to the entry, shortest text first, then id. Either list may
be empty. Not paginated. Unknown `seq` is `404`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.entry_examples import EntryExamples
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
    api_instance = kotobaya_client.DictionaryApi(api_client)
    seq = 1358280 # int | JMdict sequence number of the entry.
    limit = 10 # int | Maximum number of sentences per list. (optional) (default to 10)

    try:
        # Example sentences for an entry, from your media and from Tatoeba.
        api_response = api_instance.list_entry_examples(seq, limit=limit)
        print("The response of DictionaryApi->list_entry_examples:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DictionaryApi->list_entry_examples: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **seq** | **int**| JMdict sequence number of the entry. | 
 **limit** | **int**| Maximum number of sentences per list. | [optional] [default to 10]

### Return type

[**EntryExamples**](EntryExamples.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Example sentences. |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_kanji_words**
> KanjiWordPage list_kanji_words(literal, deck_id=deck_id, mine=mine, limit=limit, cursor=cursor)

Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17).

Rows: the live (`retired_release_id IS NULL`) `ref.dict_entry` rows `e` with a
`ref.entry_kanji` row `(e.seq, literal)`. Retired entries are never returned.

**Literal.** More than one code point (or an empty string) is `422`. Unlike `getKanji`,
a character absent from `ref.kanji` is **not** `404`: `ref.entry_kanji.literal` need not
exist in `ref.kanji`, so such a character, or any code point no entry contains (kana,
Latin...), is simply an empty page (`items: []`, `next_cursor: null`).

**Filters** (combine with AND):
- `deck_id`: only entries `e` with a live item of that deck: an `app.deck_item` row with
  `deck_id` = the parameter, `entry_seq = e.seq` and `deleted_at IS NULL`. The deck must
  be a live deck of the caller (`app.deck.deleted_at IS NULL`), else `404` - a deleted
  deck is `404` even though its rows remain. A live deck with no matching item (including
  every `smart` deck, which has no items) gives an empty page.
- `mine=true`: only entries that are a live item (as above) of at least one live deck of
  the caller, **or** that have a live vocab note (`app.note` with `entry_seq = e.seq`,
  `deleted_at IS NULL`, base type `vocab` - the same note the `note_id` overlay reports).
  Word status alone (e.g. `known` set by hand) does not count. With `deck_id` also given,
  `mine=true` adds nothing (every result is already in one of your decks).
- Note-keyed deck items (`deck_item.note_id`, `entry_seq IS NULL`) never match: only
  `entry_seq` items count, here and in `decks`.

**Order** (total; `seq` is unique):
- `deck_id` given: that deck's `deck_item.occurrence_count` descending (in-deck frequency;
  all `0` for non-media decks, so this degrades to the global order), then
  `freq_rank` ascending with NULLs last, then `seq` ascending.
- Otherwise: `freq_rank` ascending with NULLs last, then `seq` ascending.

**Paging.** Keyset pagination: the cursor encodes the sort key of the last returned row
and the `literal`, `deck_id` and `mine` it was issued for; reusing it with a different
`literal`, `deck_id` or `mine` is `422`. Pages are not a snapshot: if `occurrence_count`
or `freq_rank` changes between pages (media reparse, dictionary refresh), a row may be
skipped or repeated; clients that page through MUST de-duplicate by `seq`.
`next_cursor` is null on the last page (it MAY be non-null on a page that happens to
end exactly at the last row; the following page is then empty).

**Item.** Every `DictEntrySummary` field with the same meaning as in `searchDictionary`
(caller's `status` and `note_id` overlay), plus `decks`: the caller's live decks
(`app.deck.deleted_at IS NULL`) that hold the entry as a live item (`deck_item.deleted_at
IS NULL`), ordered by `name` in code-point order (`COLLATE "C"`), then `id`. Empty array
when none. Independent of `deck_id`/`mine`: with `deck_id`, that deck is always among
them. `decks[].occurrence_count` is that deck's `deck_item.occurrence_count`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.kanji_word_page import KanjiWordPage
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
    api_instance = kotobaya_client.DictionaryApi(api_client)
    literal = '食' # str | Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against `ref.entry_kanji.literal` (no normalisation, same rules as `getKanji`).
    deck_id = '01931f40-2000-7000-8000-000000000d01' # str | Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (`404` otherwise). (optional)
    mine = False # bool | Only entries that are a live item of at least one of your live decks or have a live vocab note. (optional) (default to False)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17).
        api_response = api_instance.list_kanji_words(literal, deck_id=deck_id, mine=mine, limit=limit, cursor=cursor)
        print("The response of DictionaryApi->list_kanji_words:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DictionaryApi->list_kanji_words: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **literal** | **str**| Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against &#x60;ref.entry_kanji.literal&#x60; (no normalisation, same rules as &#x60;getKanji&#x60;). | 
 **deck_id** | **str**| Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (&#x60;404&#x60; otherwise). | [optional] 
 **mine** | **bool**| Only entries that are a live item of at least one of your live decks or have a live vocab note. | [optional] [default to False]
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**KanjiWordPage**](KanjiWordPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of entries containing the kanji. Empty page when none do. |  -  |
**404** | No such resource. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **refresh_dictionary**
> Job refresh_dictionary(job_payload_dict_refresh=job_payload_dict_refresh)

Queue a dictionary refresh (download sources, build and load a new release).

Enqueues a job of kind `dict-refresh` (payload `JobPayloadDictRefresh`, dedupe key
`dict-refresh`). An already queued or running refresh is returned with `200`.
An absent body equals `{}`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.job import Job
from kotobaya_client.models.job_payload_dict_refresh import JobPayloadDictRefresh
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
    api_instance = kotobaya_client.DictionaryApi(api_client)
    job_payload_dict_refresh = {"freq_lists":["jpdb","bccwj"]} # JobPayloadDictRefresh |  (optional)

    try:
        # Queue a dictionary refresh (download sources, build and load a new release).
        api_response = api_instance.refresh_dictionary(job_payload_dict_refresh=job_payload_dict_refresh)
        print("The response of DictionaryApi->refresh_dictionary:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DictionaryApi->refresh_dictionary: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **job_payload_dict_refresh** | [**JobPayloadDictRefresh**](JobPayloadDictRefresh.md)|  | [optional] 

### Return type

[**Job**](Job.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | A refresh is already queued or running. |  -  |
**202** | Job queued. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **search_dictionary**
> DictEntrySummaryPage search_dictionary(q, common_only=common_only, limit=limit, cursor=cursor)

Search JMdict entries by kanji, kana, romaji or English.

`q` is trimmed and NFKC-normalised. Interpretation, in order:
1. Contains kanji or kana: katakana is folded to hiragana and matched against
   `dict_entry_form.norm` - 1 or 2 characters: exact or prefix; 3 or more: substring.
2. Otherwise (Latin letters): the server's romaji-to-kana conversion (wanakana-equivalent:
   Hepburn and common IME spellings, `nn`/`n'` for ん, doubled consonant for っ) is tried
   and, if the whole string converts, matched as in 1; the string is also matched
   case-insensitively as a substring of English glosses. Both result sets are merged.
No deinflection: the input is matched as given (`たべた` does not find `食べる` unless a
form of that entry contains `たべた`). Deinflection is the parser's job (`POST /parse`),
not this search's (C-2).
Retired entries are never returned. Order: exact form match first, then prefix, then
the rest; inside each group `freq_rank` ascending with NULLs last, then `seq`.
An empty or whitespace-only `q` is `422`. No match is an empty page.
Each item carries the caller's `status` and `note_id` overlay.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.dict_entry_summary_page import DictEntrySummaryPage
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
    api_instance = kotobaya_client.DictionaryApi(api_client)
    q = 'たべる' # str | Search text, 1 to 100 characters after trimming.
    common_only = False # bool | Only entries with `is_common = true`. (optional) (default to False)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # Search JMdict entries by kanji, kana, romaji or English.
        api_response = api_instance.search_dictionary(q, common_only=common_only, limit=limit, cursor=cursor)
        print("The response of DictionaryApi->search_dictionary:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DictionaryApi->search_dictionary: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **q** | **str**| Search text, 1 to 100 characters after trimming. | 
 **common_only** | **bool**| Only entries with &#x60;is_common &#x3D; true&#x60;. | [optional] [default to False]
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**DictEntrySummaryPage**](DictEntrySummaryPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of matching entries. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

