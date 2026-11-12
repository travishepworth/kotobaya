# kotobaya_client.JobsApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**cancel_job**](JobsApi.md#cancel_job) | **POST** /jobs/{job_id}/cancel | Cancel a queued or running job.
[**get_job**](JobsApi.md#get_job) | **GET** /jobs/{job_id} | One job with progress, result or error.
[**list_jobs**](JobsApi.md#list_jobs) | **GET** /jobs | List background jobs (imports, parses, optimizer runs, ...).
[**synthesize_speech**](JobsApi.md#synthesize_speech) | **POST** /tts | Synthesize Japanese speech (VOICEVOX) into an audio blob.


# **cancel_job**
> Job cancel_job(job_id)

Cancel a queued or running job.

Sets `status = cancelled`, `finished_at = now`. A running job's worker notices at its
next heartbeat (within 30 s) and stops; content it already published stays published.
Already cancelled: `200`, unchanged. Succeeded or failed: `409 job_not_cancellable`.


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

Ordered by `created_at` descending, then `id` descending. Jobs are kept for at least 30 days after finishing.

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

# **synthesize_speech**
> Job synthesize_speech(tts_request)

Synthesize Japanese speech (VOICEVOX) into an audio blob.

Enqueues a job of kind `tts` (dedupe key `tts:<sha256 of voice + NUL + text>`); an
equivalent queued or running job is returned with `200`. On success
`job.result.blob_hash` names an audio blob (kind `audio`, origin `tts`). The caller then
puts `[sound:<hash>]` into a note field with `PATCH /notes/{id}`; this call changes no
note.


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

