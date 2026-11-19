# kotobaya_client.JobsApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**cancel_job**](JobsApi.md#cancel_job) | **POST** /jobs/{job_id}/cancel | Cancel a queued or running job.
[**get_job**](JobsApi.md#get_job) | **GET** /jobs/{job_id} | One job with progress, result or error.
[**list_jobs**](JobsApi.md#list_jobs) | **GET** /jobs | List background jobs (imports, parses, optimizer runs, ...).
[**resolve_speech**](JobsApi.md#resolve_speech) | **POST** /tts/resolve | Look up generated speech for texts or notes, generating what is missing.
[**synthesize_speech**](JobsApi.md#synthesize_speech) | **POST** /tts | Synthesize Japanese speech (VOICEVOX) into an audio blob.


# **cancel_job**
> Job cancel_job(job_id)

Cancel a queued or running job.

Sets `status = cancelled`, `finished_at = now`. A running job's worker notices at its
next heartbeat (within 30 s) and stops; content it already published stays published.
Already cancelled: `200`, unchanged. Succeeded or failed: `409 job_not_cancellable`.
A server-internal job's id (C-9, see `Job`) answers `404 not_found`; cancel its parent
instead, which also stops the child.


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
    api_instance = kotobaya_client.JobsApi(api_client)
    job_id = '01931f42-0000-7000-8000-000000000b01' # str | Job id.

    try:
        # Cancel a queued or running job.
        api_response = api_instance.cancel_job(job_id)
        print("The response of JobsApi->cancel_job:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling JobsApi->cancel_job: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **job_id** | **str**| Job id. | 

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
**200** | The cancelled job. |  -  |
**404** | No such resource. |  -  |
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_job**
> Job get_job(job_id)

One job with progress, result or error.

The id of a server-internal job (kind not in `JobKind`, C-9, see `Job`) answers `404 not_found`, as if it did not exist.

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
    api_instance = kotobaya_client.JobsApi(api_client)
    job_id = '01931f42-0000-7000-8000-000000000b01' # str | Job id.

    try:
        # One job with progress, result or error.
        api_response = api_instance.get_job(job_id)
        print("The response of JobsApi->get_job:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling JobsApi->get_job: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **job_id** | **str**| Job id. | 

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
**200** | The job. |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_jobs**
> JobPage list_jobs(kind=kind, status=status, limit=limit, cursor=cursor)

List background jobs (imports, parses, optimizer runs, ...).

Ordered by `created_at` descending, then `id` descending. Jobs are kept for at least 30 days after finishing. Server-internal jobs (kind not in `JobKind`, C-9, see `Job`) are never listed.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.job_kind import JobKind
from kotobaya_client.models.job_page import JobPage
from kotobaya_client.models.job_status import JobStatus
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
    api_instance = kotobaya_client.JobsApi(api_client)
    kind = kotobaya_client.JobKind() # JobKind | Only jobs of this kind. (optional)
    status = [kotobaya_client.JobStatus()] # List[JobStatus] | Only jobs in one of these states (comma-separated). (optional)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # List background jobs (imports, parses, optimizer runs, ...).
        api_response = api_instance.list_jobs(kind=kind, status=status, limit=limit, cursor=cursor)
        print("The response of JobsApi->list_jobs:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling JobsApi->list_jobs: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **kind** | [**JobKind**](.md)| Only jobs of this kind. | [optional] 
 **status** | [**List[JobStatus]**](JobStatus.md)| Only jobs in one of these states (comma-separated). | [optional] 
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**JobPage**](JobPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of jobs. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **resolve_speech**
> SpeechResolveResult resolve_speech(speech_resolve_request)

Look up generated speech for texts or notes, generating what is missing.

ADR-047 (C-29). The one way a client turns "this word should have audio" into a blob
hash. Answers at once from the system table `tts_audio`; whatever is not generated yet is
queued as a `tts` job and reported `pending`, and the caller asks again. It changes no
learning data and emits no op: generated audio is derived data (SCHEMA.md 6.6).

**Items.** 1 to 200, each **exactly one** of:
- `text` (with optional `voice`): a pair. `voice` absent or null = the effective voice
  (setting `tts.voice` when valid, else `"3"`).
- `note_id`: the word audio of that note as the core `WordAudioResolver` decides it (the
  note's own `[sound:]` first, else the pair of its speech text and the effective voice).
  For clients that show a card outside a review queue.

An item with both, or neither, or `voice` together with `note_id`, is `422` (pointer
`/items/<i>`); nothing is enqueued for any item of a rejected request. Duplicate items
are allowed and answered alike.

**Result.** `items` has one `SpeechResolution` per request item, in request order:

| `status` | Meaning | `blob_hash` | Enqueues |
|---|---|---|---|
| `ready` | Generated (or the note's own audio): play `blob_hash` through `GET /blobs/{hash}`. | hash | no |
| `pending` | Not generated yet. A `tts` job for the pair is queued or running, or will be queued by a later call (see "Backlog"). Ask again after `retry_after_ms`. | null | yes, unless one exists |
| `failed` | The last attempt failed less than 600 s ago (VOICEVOX down, unknown voice). No new job until then; ask again after `retry_after_ms`. | null | no |
| `none` | Only for `note_id`: no such live note (unknown, deleted, merged away), not a `vocab` note, or no speakable text. Final for this note content. | null | no |

**Urgent.** With `urgent: true` (at most 20 items, else `422` pointer `/urgent`) the jobs
this call needs run before every non-urgent job (SCHEMA.md 6.6 "Urgent"). The review screen
uses it for the card on screen; prefetch never does.

**Backlog.** A non-urgent call enqueues only while fewer than 300 `tts` jobs are queued or
running; beyond that its missing pairs are still `pending`, with `retry_after_ms` 60000,
and are enqueued by a later call or by the server's sweep.

**Idempotent and order-free.** Repeating a call, or two callers asking for the same pair
at once, never produces two jobs for one pair (job dedupe key
`tts:<sha256 of voice + NUL + text>`) and never two different answers once it is `ready`,
except after a re-synthesis, which answers the newer blob; the older blob stays valid.

**Client rules.**
- Review screen: for the item on screen whose `word_audio` has `source: tts` and
  `blob_hash: null`, call with that `text` and `voice`, `urgent: true`, when the card is
  first shown (not at reveal). On `pending` ask again after `retry_after_ms`, then double
  the wait, at most 5 calls per showing; on `failed`, `none`, a network error or `5xx`
  stop for this showing. A card never waits for audio.
- Prefetch (Android): SCHEMA.md 6.6 "Device".
- Never write the answer into a note.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.speech_resolve_request import SpeechResolveRequest
from kotobaya_client.models.speech_resolve_result import SpeechResolveResult
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
    api_instance = kotobaya_client.JobsApi(api_client)
    speech_resolve_request = {"items":[{"note_id":"01931f40-1a2b-7c3d-8e4f-000000000a01"},{"note_id":"01931f40-1a2b-7c3d-8e4f-000000000a02"}]} # SpeechResolveRequest | 

    try:
        # Look up generated speech for texts or notes, generating what is missing.
        api_response = api_instance.resolve_speech(speech_resolve_request)
        print("The response of JobsApi->resolve_speech:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling JobsApi->resolve_speech: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **speech_resolve_request** | [**SpeechResolveRequest**](SpeechResolveRequest.md)|  | 

### Return type

[**SpeechResolveResult**](SpeechResolveResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One resolution per item, in request order. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **synthesize_speech**
> Job synthesize_speech(tts_request)

Synthesize Japanese speech (VOICEVOX) into an audio blob.

Enqueues a job of kind `tts` (dedupe key `tts:<sha256 of voice + NUL + text>`); an
equivalent queued or running job is returned with `200`. On success
`job.result.blob_hash` names an audio blob (kind `audio`, origin `tts`). This call changes
no note. A caller that wants the sound stored **in** a note puts `[sound:<hash>]` into a
field with `PATCH /notes/{id}`; word audio of vocab cards needs none of that (ADR-047: see
`QueueItem.word_audio` and `resolveSpeech`).

`voice` defaults to the effective voice (setting `tts.voice` when it is a string of 1-9
decimal digits, else `"3"`; SCHEMA.md 6.6); the server writes the resolved voice into the
job payload. The worker records every success in the system table `tts_audio`
(`(voice, text)` to blob), so a later request for the same pair, by this operation or by
`resolveSpeech`, does not call VOICEVOX again. A `voice` that is not decimal digits is
accepted here for compatibility and fails the job.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.job import Job
from kotobaya_client.models.tts_request import TtsRequest
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
    api_instance = kotobaya_client.JobsApi(api_client)
    tts_request = {"text":"パンを食べる"} # TtsRequest | 

    try:
        # Synthesize Japanese speech (VOICEVOX) into an audio blob.
        api_response = api_instance.synthesize_speech(tts_request)
        print("The response of JobsApi->synthesize_speech:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling JobsApi->synthesize_speech: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **tts_request** | [**TtsRequest**](TtsRequest.md)|  | 

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
**202** | Job queued. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

