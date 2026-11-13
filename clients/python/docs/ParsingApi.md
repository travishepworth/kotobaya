# kotobaya_client.ParsingApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**delete_parse_override**](ParsingApi.md#delete_parse_override) | **DELETE** /parse-overrides/{override_id} | Delete a tokenisation correction (permanent).
[**list_parse_overrides**](ParsingApi.md#list_parse_overrides) | **GET** /parse-overrides | List your tokenisation corrections.
[**parse_text**](ParsingApi.md#parse_text) | **POST** /parse | Parse text - tokens, matched entries and their known status.
[**put_parse_override**](ParsingApi.md#put_parse_override) | **PUT** /parse-overrides/{override_id} | Create or replace a tokenisation correction.


# **delete_parse_override**
> delete_parse_override(override_id)

Delete a tokenisation correction (permanent).

Server emits `parse_override.deleted`. Already deleted - `204`, no op. Unknown id - `404`.

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
    api_instance = kotobaya_client.ParsingApi(api_client)
    override_id = '01931f40-6000-7000-8000-000000000801' # str | Override id (client-generated UUIDv7).

    try:
        # Delete a tokenisation correction (permanent).
        api_instance.delete_parse_override(override_id)
    except Exception as e:
        print("Exception when calling ParsingApi->delete_parse_override: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **override_id** | **str**| Override id (client-generated UUIDv7). | 

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

# **list_parse_overrides**
> ParseOverridePage list_parse_overrides(media_id=media_id, scope=scope, include_deleted=include_deleted, limit=limit, cursor=cursor)

List your tokenisation corrections.

Ordered by `updated_at` descending, then `id`.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.parse_override_page import ParseOverridePage
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
    api_instance = kotobaya_client.ParsingApi(api_client)
    media_id = '01931f40-3000-7000-8000-000000000e01' # str | Only overrides scoped to this media (including sentence-scoped ones inside it). (optional)
    scope = 'global' # str | Only overrides of this scope. (optional)
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # List your tokenisation corrections.
        api_response = api_instance.list_parse_overrides(media_id=media_id, scope=scope, include_deleted=include_deleted, limit=limit, cursor=cursor)
        print("The response of ParsingApi->list_parse_overrides:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling ParsingApi->list_parse_overrides: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **media_id** | **str**| Only overrides scoped to this media (including sentence-scoped ones inside it). | [optional] 
 **scope** | **str**| Only overrides of this scope. | [optional] 
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**ParseOverridePage**](ParseOverridePage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of overrides. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **parse_text**
> ParseResult parse_text(parse_request)

Parse text - tokens, matched entries and their known status.

Runs the same pipeline as media parsing (normalise, Sudachi, deinflect, match JMdict,
apply global parse overrides) synchronously and stores nothing. The text is split into
sentences; token offsets are relative to each sentence's `text`. `entries` holds one
summary (with your `status` and `note_id`) per distinct `seq` among the tokens.
Text of 1 to 20,000 characters (code points); longer is `413 payload_too_large`.
Tokens, sentence splitting and override application: `contracts/api/PARSER.md` (C-12).
The server forwards the text to the worker parse service (`contracts/api/worker-parse.yaml`,
`PARSER_URL`, timeout `PARSER_TIMEOUT_MS`, default 10 s) and passes its sentences through
unchanged. If the parser is not configured, unreachable, times out, is busy or starting,
or answers with anything but `200`/`413`: `503 unavailable` (`Retry-After: 5` when busy or
starting).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.parse_request import ParseRequest
from kotobaya_client.models.parse_result import ParseResult
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
    api_instance = kotobaya_client.ParsingApi(api_client)
    parse_request = {"text":"パンを食べる。"} # ParseRequest | 

    try:
        # Parse text - tokens, matched entries and their known status.
        api_response = api_instance.parse_text(parse_request)
        print("The response of ParsingApi->parse_text:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling ParsingApi->parse_text: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **parse_request** | [**ParseRequest**](ParseRequest.md)|  | 

### Return type

[**ParseResult**](ParseResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Parse result. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **put_parse_override**
> ParseOverride put_parse_override(override_id, parse_override_put)

Create or replace a tokenisation correction.

Server emits `parse_override.put` (whole-row last-writer-wins). The token surfaces must
concatenate exactly to `match_text` (`422` otherwise). `sentence_id` requires
`media_id`. Scope references are soft and not checked. Putting a deleted id is
`410 gone` (deletes are permanent; use a new id). Overrides take effect at the next
parse of the affected media (`POST /media/{id}/reparse`) and immediately in
`POST /parse` (global ones).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.parse_override import ParseOverride
from kotobaya_client.models.parse_override_put import ParseOverridePut
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
    api_instance = kotobaya_client.ParsingApi(api_client)
    override_id = '01931f40-6000-7000-8000-000000000801' # str | Override id (client-generated UUIDv7).
    parse_override_put = {"match_text":"食べかけ","media_id":"01931f40-3000-7000-8000-000000000e01","note":"split compound","tokens":[{"lemma":"食べる","seq":1358280,"surface":"食べ"},{"lemma":"かける","seq":1207610,"surface":"かけ"}]} # ParseOverridePut | 

    try:
        # Create or replace a tokenisation correction.
        api_response = api_instance.put_parse_override(override_id, parse_override_put)
        print("The response of ParsingApi->put_parse_override:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling ParsingApi->put_parse_override: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **override_id** | **str**| Override id (client-generated UUIDv7). | 
 **parse_override_put** | [**ParseOverridePut**](ParseOverridePut.md)|  | 

### Return type

[**ParseOverride**](ParseOverride.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The override as stored. |  * Kotobaya-Seq -  <br>  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

