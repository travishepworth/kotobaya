# kotobaya_client.ExportApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**export_all**](ExportApi.md#export_all) | **GET** /export | Full JSON dump of your data.
[**export_anki**](ExportApi.md#export_anki) | **GET** /export/anki | Export notes and cards as an Anki package (.apkg).


# **export_all**
> ExportDump export_all(include=include)

Full JSON dump of your data.

Streams one JSON document (schema `ExportDump`) read in a single repeatable-read
snapshot. `tables` holds every row of every learning-data table plus `media` and
`parse_override`, tombstones and merge losers included, each row with every column of
`V1__init.sql` except generated ones (`note.search_text`): arrays as JSON arrays, `jsonb`
as JSON, timestamps as RFC 3339 UTC, including `clocks`/`hlc` (so a dump can seed a
replica). With `include=media_content` the bulk tables `media_segment`, `sentence`,
`occurrence` and detected `sentence_grammar_span` rows are added (can be hundreds of
MB). Never included: `device`, `api_token`, `change`, `job`, `blob` rows, blob bytes,
`ref.*`. Sent with `Content-Disposition: attachment; filename="kotobaya-export-<UTC date>.json"`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.export_dump import ExportDump
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
    api_instance = kotobaya_client.ExportApi(api_client)
    include = ['[\"media_content\"]'] # List[str] | Optional extra sections, comma-separated. (optional)

    try:
        # Full JSON dump of your data.
        api_response = api_instance.export_all(include=include)
        print("The response of ExportApi->export_all:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling ExportApi->export_all: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **include** | [**List[str]**](str.md)| Optional extra sections, comma-separated. | [optional] 

### Return type

[**ExportDump**](ExportDump.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The dump. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **export_anki**
> bytes export_anki(query=query)

Export notes and cards as an Anki package (.apkg).

Builds an `.apkg` (Anki 2.1 `collection.anki21` format) of live notes and cards: one
Anki note type per Kotobaya note type (fields in order, one card template per card
kind, rendered templates from the core), tags, referenced audio and image blobs as
media files, and the review log (`revlog`; `manual`, `drill`, `cram` and undone rows are
left out). Scheduling is exported as FSRS memory state plus due date. With `query`, only
cards matching the card search string (and their notes) are exported; a query that does
not parse is `422 invalid_query`. No match still yields a valid, empty package.
Sent with `Content-Disposition: attachment; filename="kotobaya-<UTC date>.apkg"`.


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
    api_instance = kotobaya_client.ExportApi(api_client)
    query = 'deck:\"Frieren\"' # str | Card search string restricting what is exported. (optional)

    try:
        # Export notes and cards as an Anki package (.apkg).
        api_response = api_instance.export_anki(query=query)
        print("The response of ExportApi->export_anki:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling ExportApi->export_anki: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **query** | **str**| Card search string restricting what is exported. | [optional] 

### Return type

**bytes**

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/octet-stream, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The package. |  -  |
**422** | The card search string does not parse (or another validation error; see &#x60;code&#x60;). |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

