# kotobaya_client.MediaApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**delete_media**](MediaApi.md#delete_media) | **DELETE** /media/{media_id} | Delete a media, its deck and its parsed content.
[**get_media**](MediaApi.md#get_media) | **GET** /media/{media_id} | One media with its segments.
[**get_media_coverage**](MediaApi.md#get_media_coverage) | **GET** /media/{media_id}/coverage | Coverage of a media (or one segment) by your word statuses.
[**get_sentence**](MediaApi.md#get_sentence) | **GET** /sentences/{sentence_id} | One sentence with tokens and grammar spans.
[**import_jpdb_deck**](MediaApi.md#import_jpdb_deck) | **POST** /media/import/jpdb | Import a pre-parsed jpdb media deck (vocabulary with occurrence counts).
[**import_jpdb_history**](MediaApi.md#import_jpdb_history) | **POST** /import/jpdb/history | Import jpdb review history and word statuses from a jpdb export file.
[**import_media**](MediaApi.md#import_media) | **POST** /media/import | Import your own file(s), a URL or pasted text as media (worker job).
[**list_media**](MediaApi.md#list_media) | **GET** /media | List the media library.
[**list_media_sentences**](MediaApi.md#list_media_sentences) | **GET** /media/{media_id}/sentences | Sentences of a media, optionally only i+1 sentences.
[**list_sentences**](MediaApi.md#list_sentences) | **GET** /sentences | Search sentences across all media.
[**reparse_media**](MediaApi.md#reparse_media) | **POST** /media/{media_id}/reparse | Re-parse a media (after parse overrides or a dictionary refresh).
[**update_media**](MediaApi.md#update_media) | **PATCH** /media/{media_id} | Change a media&#39;s title, kind or cover.


# **delete_media**
> delete_media(media_id)

Delete a media, its deck and its parsed content.

Server emits `media.deleted` and, as a consequence in the same transaction,
`deck.deleted` for the media deck; then hard-deletes the media's segments, sentences,
occurrences and detected grammar spans (SCHEMA.md 4.3). Notes mined from it, manual
spans and reviews stay. Queued or running jobs for the media are cancelled. Already
deleted: `204`, no op.


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
    api_instance = kotobaya_client.MediaApi(api_client)
    media_id = '01931f40-3000-7000-8000-000000000e01' # str | Media id.

    try:
        # Delete a media, its deck and its parsed content.
        api_instance.delete_media(media_id)
    except Exception as e:
        print("Exception when calling MediaApi->delete_media: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **media_id** | **str**| Media id. | 

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

# **get_media**
> Media get_media(media_id, include_deleted=include_deleted)

One media with its segments.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.media import Media
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
    api_instance = kotobaya_client.MediaApi(api_client)
    media_id = '01931f40-3000-7000-8000-000000000e01' # str | Media id.
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)

    try:
        # One media with its segments.
        api_response = api_instance.get_media(media_id, include_deleted=include_deleted)
        print("The response of MediaApi->get_media:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MediaApi->get_media: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **media_id** | **str**| Media id. | 
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]

### Return type

[**Media**](Media.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The media. |  -  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_media_coverage**
> Coverage get_media_coverage(media_id, segment_id=segment_id, hide=hide)

Coverage of a media (or one segment) by your word statuses.

Computed at request time from `occurrence`, `media_segment` and `word_status`, so it
always reflects the current statuses. Definitions are on the `Coverage` schema. With
`segment_id` the numbers cover only that segment and `segments` has that one element.
Media with `content_version = 0` (not parsed yet) returns all-zero numbers.


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
    api_instance = kotobaya_client.MediaApi(api_client)
    media_id = '01931f40-3000-7000-8000-000000000e01' # str | Media id.
    segment_id = '01931f40-3100-7000-8000-000000000e11' # str | Restrict to one segment of this media (`404` if it is not one). (optional)
    hide = [ignored] # List[str] | Classes left out of every coverage number, comma-separated. `names`, `particles`, `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`; `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide nothing.  (optional) (default to [ignored])

    try:
        # Coverage of a media (or one segment) by your word statuses.
        api_response = api_instance.get_media_coverage(media_id, segment_id=segment_id, hide=hide)
        print("The response of MediaApi->get_media_coverage:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MediaApi->get_media_coverage: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **media_id** | **str**| Media id. | 
 **segment_id** | **str**| Restrict to one segment of this media (&#x60;404&#x60; if it is not one). | [optional] 
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
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_sentence**
> Sentence get_sentence(sentence_id)

One sentence with tokens and grammar spans.

`404` when the sentence does not exist (never imported, or purged with its media or by a re-parse).

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.sentence import Sentence
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
    api_instance = kotobaya_client.MediaApi(api_client)
    sentence_id = '01931f40-5000-7000-8000-000000000701' # str | Sentence id.

    try:
        # One sentence with tokens and grammar spans.
        api_response = api_instance.get_sentence(sentence_id)
        print("The response of MediaApi->get_sentence:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MediaApi->get_sentence: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **sentence_id** | **str**| Sentence id. | 

### Return type

[**Sentence**](Sentence.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The sentence. |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **import_jpdb_deck**
> Job import_jpdb_deck(jpdb_deck_import_request)

Import a pre-parsed jpdb media deck (vocabulary with occurrence counts).

Enqueues a job of kind `jpdb-deck-import` (dedupe key `jpdb-deck-import:<jpdb_id>`) and
returns it with `202`. If a live media with this `jpdb_id` exists the job updates it
(same `media_id`, new content version); otherwise `media_id` is pre-allocated as in
`POST /media/import`. The job emits, as server ops, `media.created` and `deck.created`
(first import only), `media.content_published`, `deck.content_published`. The result has
one segment of kind `whole` and no sentences. Vocabulary that maps to no JMdict entry is
listed in the job result (`unmapped`), never silently dropped.
An equivalent queued or running job is returned with `200`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.job import Job
from kotobaya_client.models.jpdb_deck_import_request import JpdbDeckImportRequest
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
    api_instance = kotobaya_client.MediaApi(api_client)
    jpdb_deck_import_request = {"deck":{"priority":5},"jpdb_id":"anime/5370","kind":"anime"} # JpdbDeckImportRequest | 

    try:
        # Import a pre-parsed jpdb media deck (vocabulary with occurrence counts).
        api_response = api_instance.import_jpdb_deck(jpdb_deck_import_request)
        print("The response of MediaApi->import_jpdb_deck:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MediaApi->import_jpdb_deck: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **jpdb_deck_import_request** | [**JpdbDeckImportRequest**](JpdbDeckImportRequest.md)|  | 

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
**200** | An equivalent job is already queued or running. |  -  |
**202** | Import job queued. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **import_jpdb_history**
> Job import_jpdb_history(jpdb_history_import_request)

Import jpdb review history and word statuses from a jpdb export file.

Upload the export JSON with `PUT /blobs/{hash}` (kind `upload`), then call this.
Enqueues a job of kind `jpdb-history-import` (dedupe key `jpdb-history-import`). The job
emits, as server ops: `note.created` and `card.created` for vocabulary without a note,
`review.logged` for every historical review (`import_source = jpdb`, deterministic
review ids derived from the export so that re-running imports nothing twice), and
`word.status_set` (payload `origin = jpdb_import`, stored as `override_origin`) for known, never-forget and
blacklisted words. Records that cannot be mapped are counted and listed in the job
result. An equivalent queued or running job is returned with `200`.

**Export arrays (C-2; spikes/jpdb-map/REPORT.md "Export structure").** The export is one
object whose array members are read as follows; any other member is ignored, a missing
array counts as empty, and `from_anki` is ignored.
| Array | Row | Becomes |
|---|---|---|
| `cards_vocabulary_jp_en`, `cards_vocabulary_en_jp` | `vid`, `spelling`, `reading`, `reviews[]` | vocab note (built-in Vocab type, `entry_seq` = the mapped JMdict seq, `origin = jpdb_import`) |
| `cards_kanji_char_keyword` | `character`, `reviews[]` | kanji note, card kind `keyword` (kanji → keyword) |
| `cards_kanji_keyword_char` | `character`, `reviews[]` | kanji note, card kind `writing` (keyword → kanji) |

**Kanji rows.** A row whose `character` is exactly one Unicode code point maps to the
live note with that `kanji_literal` if one exists (counted `kanji_notes_existing`, once
per note), else to a new note: `note.created` with `note_type_id` = the built-in Kanji
type (`00000000-0000-7000-8000-000000000102`, base type `kanji`), `kanji_literal` =
`character`, `fields` = `{"literal": character}` (no `keyword`, `readings`, `notes`: the
renderer fills them from `ref.kanji`, `RenderInput.kanji`), `origin = jpdb_import`. Both
arrays naming the same character share one note. The job then ensures a live card of the
row's kind (`keyword` or `writing`) - only that kind, never the note type's other default
kinds. Any other `character` is listed under `kanji_unmapped`. A character missing from
`ref.kanji` is still imported.

**Grades** (each element of `reviews[]`; `timestamp` is Unix seconds UTC → `reviewed_at`
= timestamp × 1000, `utc_offset_min = null`, `duration_ms = null`, `review_type =
scheduled`, `import_source = jpdb`):
| `grade` | Effect |
|---|---|
| `nothing` | `review.logged` rating 1, button `nothing` |
| `something` | rating 1, button `something` |
| `hard` | rating 2, button `hard` (FSRS Hard; ADR-008) |
| `okay` | rating 3, button `okay` |
| `easy` | rating 4, button `easy` |
| `pass` | rating 3, button `null` |
| `fail` | rating 1, button `null` |
| `unknown` | no review: the row's note and card are created (as for any mapped row), nothing else |
| `known` | no review. Vocab: `word.status_set` status `known`, `origin = jpdb_import` (if `import_word_status`). Kanji: nothing beyond the note and card |
| anything else | nothing; counted `reviews_unknown_grade` |

A mapped vocab row gets its note and card iff it has at least one event other than
`known` (`vocabulary_carded`); one whose events are all `known` gets only the word
status (`vocabulary_status_only`). Every mapped kanji row gets its note and card
(`kanji_carded`), even if its only events are `known` (kanji have no word status). With `import_reviews = false` nothing but
word statuses is written: rows are bucketed by the same rules (`*_carded` then means
"would get a note and card"), rating events count as `reviews_skipped`, status events
as usual.

**Counting.** Every input record - each row of the four arrays and each element of its
`reviews[]` - is counted in exactly one bucket of `JobResultJpdbHistory` (its description
gives the three sums that must hold).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.job import Job
from kotobaya_client.models.jpdb_history_import_request import JpdbHistoryImportRequest
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
    api_instance = kotobaya_client.MediaApi(api_client)
    jpdb_history_import_request = {"blob_hash":"4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a"} # JpdbHistoryImportRequest | 

    try:
        # Import jpdb review history and word statuses from a jpdb export file.
        api_response = api_instance.import_jpdb_history(jpdb_history_import_request)
        print("The response of MediaApi->import_jpdb_history:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MediaApi->import_jpdb_history: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **jpdb_history_import_request** | [**JpdbHistoryImportRequest**](JpdbHistoryImportRequest.md)|  | 

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
**200** | An import is already queued or running. |  -  |
**202** | Import job queued. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **import_media**
> Job import_media(media_import_request)

Import your own file(s), a URL or pasted text as media (worker job).

Enqueues a job of kind `media-import` and returns it with `202`. Files are uploaded first
with `PUT /blobs/{hash}` (kind `upload`) and referenced by hash; there is no multipart
variant.

- `source: file` - `files` required (1 to 500). Each file becomes one segment in array
  order (an EPUB becomes one segment per chapter, in book order). Every `blob_hash` must
  exist (`422` naming the element otherwise).
- `source: url` - `url` required (http/https); fetched by the worker with readability
  extraction; one segment.
- `source: paste` - `text` required (1 to 1,000,000 characters); one segment.
- New media: `title` and `kind` required. The server pre-allocates `media_id` (returned
  in `job.payload.media_id`); the job creates the `media` row, its deck (`deck.id` =
  `media_id`) and all content, emitting as server ops `media.created`, `deck.created`,
  `media.content_published`, `deck.content_published`. `GET /media/{media_id}` is `404`
  until the job has created the row.
- Existing media: pass `media_id` (must be live, with `source` equal to this request's);
  the new segments are appended after the existing ones and a new content version is
  published. `title`/`kind`/`deck` must then be absent.
- Dedupe key `media-import:<media_id>`: a second import into the same media while one is
  queued or running is `409 already_exists` (`existing_id` = the job id).
- **Idempotent retry: `import_id`.** Optional client-generated UUIDv7, minted once per
  user action and reused on every retry; it becomes the job's `id`, and for new media
  also the new `media_id` (and so the deck id). If a job with this id already exists
  (any status), or - for new media - a `media` row with this id exists (live or deleted),
  the server enqueues nothing and answers `200` with that job (or, if the job row is
  gone, the most recent `media-import` job for that media; `404` if none), whatever the
  rest of the body says. Without `import_id` a retry after a lost `202` imports twice
  (a second media and deck); clients that may retry MUST send it. An `import_id` used by
  a job of another kind is `409 already_exists` (`existing_id` = that job).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.job import Job
from kotobaya_client.models.media_import_request import MediaImportRequest
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
    api_instance = kotobaya_client.MediaApi(api_client)
    media_import_request = {"files":[{"blob_hash":"fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9","filename":"ep03.srt"}],"media_id":"01931f40-3000-7000-8000-000000000e01","source":"file"} # MediaImportRequest | 

    try:
        # Import your own file(s), a URL or pasted text as media (worker job).
        api_response = api_instance.import_media(media_import_request)
        print("The response of MediaApi->import_media:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MediaApi->import_media: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **media_import_request** | [**MediaImportRequest**](MediaImportRequest.md)|  | 

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
**200** | Retry with a known &#x60;import_id&#x60; - the existing job; nothing was enqueued. |  -  |
**202** | Import job queued. |  -  |
**404** | No such resource. |  -  |
**409** | A natural-key collision; &#x60;existing_id&#x60; names the row that holds the key. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_media**
> MediaPage list_media(kind=kind, q=q, include_deleted=include_deleted, limit=limit, cursor=cursor)

List the media library.

Ordered by `created_at` descending, then `id` descending.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.media_kind import MediaKind
from kotobaya_client.models.media_page import MediaPage
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
    api_instance = kotobaya_client.MediaApi(api_client)
    kind = kotobaya_client.MediaKind() # MediaKind | Only media of this kind. (optional)
    q = 'フリーレン' # str | Case-insensitive substring of the title. (optional)
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # List the media library.
        api_response = api_instance.list_media(kind=kind, q=q, include_deleted=include_deleted, limit=limit, cursor=cursor)
        print("The response of MediaApi->list_media:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MediaApi->list_media: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **kind** | [**MediaKind**](.md)| Only media of this kind. | [optional] 
 **q** | **str**| Case-insensitive substring of the title. | [optional] 
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**MediaPage**](MediaPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of media. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_media_sentences**
> SentencePage list_media_sentences(media_id, filter=filter, segment_id=segment_id, q=q, entry_seq=entry_seq, grammar_point_id=grammar_point_id, limit=limit, cursor=cursor)

Sentences of a media, optionally only i+1 sentences.

Same as `GET /sentences` with `media_id` fixed. Ordered by segment `ordinal`, then
sentence `ordinal`. jpdb-imported media have no sentences (empty page).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.sentence_page import SentencePage
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
    api_instance = kotobaya_client.MediaApi(api_client)
    media_id = '01931f40-3000-7000-8000-000000000e01' # str | Media id.
    filter = 'i+1' # str | `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`.  (optional)
    segment_id = '01931f40-3100-7000-8000-000000000e11' # str | Only sentences of this segment. (optional)
    q = '食べ' # str | Substring of the sentence text (exact characters, no normalisation). (optional)
    entry_seq = 1358280 # int | Only sentences with a token mapped to this entry. (optional)
    grammar_point_id = '01931f40-4000-7000-8000-000000000901' # str | Only sentences with a live span of this grammar point. (optional)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # Sentences of a media, optionally only i+1 sentences.
        api_response = api_instance.list_media_sentences(media_id, filter=filter, segment_id=segment_id, q=q, entry_seq=entry_seq, grammar_point_id=grammar_point_id, limit=limit, cursor=cursor)
        print("The response of MediaApi->list_media_sentences:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MediaApi->list_media_sentences: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **media_id** | **str**| Media id. | 
 **filter** | **str**| &#x60;all&#x60; (default); &#x60;i+1&#x60; &#x3D; sentences with exactly one unknown word; &#x60;i+0&#x60; &#x3D; none. \&quot;Unknown word\&quot; &#x3D; a distinct entry among the sentence&#39;s tokens whose effective word status is &#x60;new&#x60;; tokens of class &#x60;particle&#x60;, &#x60;auxiliary&#x60;, &#x60;name&#x60;, unmapped tokens and entries with status &#x60;blacklisted&#x60;/&#x60;ignored&#x60; are not counted. Because an unencoded &#x60;+&#x60; in a query string decodes to a space, servers MUST also accept &#x60;i 1&#x60; and &#x60;i 0&#x60;.  | [optional] 
 **segment_id** | **str**| Only sentences of this segment. | [optional] 
 **q** | **str**| Substring of the sentence text (exact characters, no normalisation). | [optional] 
 **entry_seq** | **int**| Only sentences with a token mapped to this entry. | [optional] 
 **grammar_point_id** | **str**| Only sentences with a live span of this grammar point. | [optional] 
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**SentencePage**](SentencePage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of sentences. |  -  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_sentences**
> SentencePage list_sentences(media_id=media_id, filter=filter, segment_id=segment_id, q=q, entry_seq=entry_seq, grammar_point_id=grammar_point_id, limit=limit, cursor=cursor)

Search sentences across all media.

All filters combine with AND. Ordered by media `created_at` descending, media id, segment
`ordinal`, sentence `ordinal`. Sentences of deleted media do not exist any more and are
never returned. LLM-generated sentences (`origin = generated`) are included and must be
labelled by clients. Each sentence carries its live grammar spans (detected and manual).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.sentence_page import SentencePage
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
    api_instance = kotobaya_client.MediaApi(api_client)
    media_id = '01931f40-3000-7000-8000-000000000e01' # str | Only sentences of this media. (optional)
    filter = 'i+1' # str | `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`.  (optional)
    segment_id = '01931f40-3100-7000-8000-000000000e11' # str | Only sentences of this segment. (optional)
    q = '食べ' # str | Substring of the sentence text (exact characters, no normalisation). (optional)
    entry_seq = 1358280 # int | Only sentences with a token mapped to this entry. (optional)
    grammar_point_id = '01931f40-4000-7000-8000-000000000901' # str | Only sentences with a live span of this grammar point. (optional)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # Search sentences across all media.
        api_response = api_instance.list_sentences(media_id=media_id, filter=filter, segment_id=segment_id, q=q, entry_seq=entry_seq, grammar_point_id=grammar_point_id, limit=limit, cursor=cursor)
        print("The response of MediaApi->list_sentences:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MediaApi->list_sentences: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **media_id** | **str**| Only sentences of this media. | [optional] 
 **filter** | **str**| &#x60;all&#x60; (default); &#x60;i+1&#x60; &#x3D; sentences with exactly one unknown word; &#x60;i+0&#x60; &#x3D; none. \&quot;Unknown word\&quot; &#x3D; a distinct entry among the sentence&#39;s tokens whose effective word status is &#x60;new&#x60;; tokens of class &#x60;particle&#x60;, &#x60;auxiliary&#x60;, &#x60;name&#x60;, unmapped tokens and entries with status &#x60;blacklisted&#x60;/&#x60;ignored&#x60; are not counted. Because an unencoded &#x60;+&#x60; in a query string decodes to a space, servers MUST also accept &#x60;i 1&#x60; and &#x60;i 0&#x60;.  | [optional] 
 **segment_id** | **str**| Only sentences of this segment. | [optional] 
 **q** | **str**| Substring of the sentence text (exact characters, no normalisation). | [optional] 
 **entry_seq** | **int**| Only sentences with a token mapped to this entry. | [optional] 
 **grammar_point_id** | **str**| Only sentences with a live span of this grammar point. | [optional] 
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**SentencePage**](SentencePage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of sentences. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **reparse_media**
> Job reparse_media(media_id)

Re-parse a media (after parse overrides or a dictionary refresh).

Enqueues a job of kind `media-parse` (dedupe key `media-parse:<media_id>`); an equivalent
queued or running job is returned with `200`. The job keeps segment and sentence ids as
SCHEMA.md requires and makes the server emit `media.content_published` and
`deck.content_published`. Media with `source = jpdb` has nothing to parse:
`409 not_applicable`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.job import Job
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
    api_instance = kotobaya_client.MediaApi(api_client)
    media_id = '01931f40-3000-7000-8000-000000000e01' # str | Media id.

    try:
        # Re-parse a media (after parse overrides or a dictionary refresh).
        api_response = api_instance.reparse_media(media_id)
        print("The response of MediaApi->reparse_media:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MediaApi->reparse_media: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **media_id** | **str**| Media id. | 

### Return type

[**Job**](Job.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | A parse is already queued or running. |  -  |
**202** | Parse job queued. |  -  |
**404** | No such resource. |  -  |
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **update_media**
> Media update_media(media_id, media_patch)

Change a media's title, kind or cover.

Server emits one `media.updated` with the registers present (`title`, `kind`,
`cover_blob_hash`). `cover_blob_hash` must be an existing blob of kind `image`, or `null`
to remove the cover. The media deck's name is not changed (patch the deck). Empty body:
`422`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.media import Media
from kotobaya_client.models.media_patch import MediaPatch
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
    api_instance = kotobaya_client.MediaApi(api_client)
    media_id = '01931f40-3000-7000-8000-000000000e01' # str | Media id.
    media_patch = {"kind":"anime","title":"葬送のフリーレン (S1)"} # MediaPatch | 

    try:
        # Change a media's title, kind or cover.
        api_response = api_instance.update_media(media_id, media_patch)
        print("The response of MediaApi->update_media:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MediaApi->update_media: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **media_id** | **str**| Media id. | 
 **media_patch** | [**MediaPatch**](MediaPatch.md)|  | 

### Return type

[**Media**](Media.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The updated media. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

