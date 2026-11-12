# kotobaya_client.BlobsApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**get_blob**](BlobsApi.md#get_blob) | **GET** /blobs/{hash} | Download a blob.
[**put_blob**](BlobsApi.md#put_blob) | **PUT** /blobs/{hash} | Upload a blob under its SHA-256.
[**query_blobs**](BlobsApi.md#query_blobs) | **POST** /blobs/query | Ask which of a set of blobs the server already has.


# **get_blob**
> bytes get_blob(hash)

Download a blob.

Returns the bytes with the stored `Content-Type`, `Content-Length`,
`ETag: "<hash>"` and `Cache-Control: private, max-age=31536000, immutable`. Supports
single-range `Range` requests (`206`) and `If-None-Match` (`304`). `HEAD` behaves the
same without a body (not listed separately). The server may answer `302` to a
short-lived pre-signed object-store URL; clients MUST follow redirects and MUST NOT
forward the `Authorization` header to the redirect target.


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
    api_instance = kotobaya_client.BlobsApi(api_client)
    hash = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08' # str | Lowercase hex SHA-256 of the blob's bytes.

    try:
        # Download a blob.
        api_response = api_instance.get_blob(hash)
        print("The response of BlobsApi->get_blob:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling BlobsApi->get_blob: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **hash** | **str**| Lowercase hex SHA-256 of the blob&#39;s bytes. | 

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
**200** | The blob&#39;s bytes. |  -  |
**206** | The requested byte range. |  -  |
**302** | Redirect to a pre-signed object-store URL valid for at least 60 seconds. |  * Location - Pre-signed URL. <br>  |
**304** | Not modified (&#x60;If-None-Match&#x60; matched). |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **put_blob**
> Blob put_blob(kind, hash, body)

Upload a blob under its SHA-256.

The body is the raw bytes; `Content-Type` is stored as the blob's content type
(`application/octet-stream` when the caller does not know better). The server hashes the
bytes while storing; a mismatch with `{hash}` is `422 hash_mismatch` and nothing is
kept. Uploading a hash that exists is a no-op returning `200` with the stored metadata
(the new body is discarded, `kind` and content type are not changed). Maximum size
`limits.max_blob_bytes` from `GET /meta` (`413`). `origin` is always `upload`.
Direct write to the system table `blob`; no op. Needs `write` or `import`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.blob import Blob
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
    api_instance = kotobaya_client.BlobsApi(api_client)
    kind = 'audio' # str | What the blob is - `audio` or `image` for card and media assets, `upload` for a raw import file. (`snapshot` blobs are produced only by server jobs.)
    hash = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08' # str | Lowercase hex SHA-256 of the blob's bytes.
    body = (binary) # bytes | 

    try:
        # Upload a blob under its SHA-256.
        api_response = api_instance.put_blob(kind, hash, body)
        print("The response of BlobsApi->put_blob:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling BlobsApi->put_blob: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **kind** | **str**| What the blob is - &#x60;audio&#x60; or &#x60;image&#x60; for card and media assets, &#x60;upload&#x60; for a raw import file. (&#x60;snapshot&#x60; blobs are produced only by server jobs.) | 
 **hash** | **str**| Lowercase hex SHA-256 of the blob&#39;s bytes. | 
 **body** | **bytes**|  | 

### Return type

[**Blob**](Blob.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/octet-stream
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The blob already existed. |  -  |
**201** | Stored. |  -  |
**413** | The body exceeds a limit from &#x60;GET /meta&#x60;. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **query_blobs**
> BlobQueryResult query_blobs(blob_query)

Ask which of a set of blobs the server already has.

Lets a client skip uploads. 1 to 1000 hashes; duplicates are ignored. `present` and `missing` partition the distinct input, each sorted ascending.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.blob_query import BlobQuery
from kotobaya_client.models.blob_query_result import BlobQueryResult
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
    api_instance = kotobaya_client.BlobsApi(api_client)
    blob_query = {"hashes":["9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08","2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae"]} # BlobQuery | 

    try:
        # Ask which of a set of blobs the server already has.
        api_response = api_instance.query_blobs(blob_query)
        print("The response of BlobsApi->query_blobs:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling BlobsApi->query_blobs: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **blob_query** | [**BlobQuery**](BlobQuery.md)|  | 

### Return type

[**BlobQueryResult**](BlobQueryResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Which hashes exist. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

