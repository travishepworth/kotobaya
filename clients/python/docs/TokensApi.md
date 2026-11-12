# kotobaya_client.TokensApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**create_token**](TokensApi.md#create_token) | **POST** /tokens | Create a personal access token.
[**get_token**](TokensApi.md#get_token) | **GET** /tokens/{token_id} | One credential.
[**list_tokens**](TokensApi.md#list_tokens) | **GET** /tokens | List credentials of every kind (never the token strings or hashes).
[**revoke_token**](TokensApi.md#revoke_token) | **DELETE** /tokens/{token_id} | Revoke a credential of any kind.


# **create_token**
> TokenCreated create_token(token_create)

Create a personal access token.

Returns the token string once. `scopes` must be non-empty, without duplicates.
`expires_at`, when given, must be in the future. With `with_device: true` the server also
creates a `device` row of kind `script` (server-generated id, same name) and binds the
token to it, which allows the token to use `POST /sync/push`; revoking the token does not
revoke that device, revoking the device revokes the token. Names need not be unique.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.token_create import TokenCreate
from kotobaya_client.models.token_created import TokenCreated
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
    api_instance = kotobaya_client.TokensApi(api_client)
    token_create = {"name":"nightly-import","scopes":["read","import"]} # TokenCreate | 

    try:
        # Create a personal access token.
        api_response = api_instance.create_token(token_create)
        print("The response of TokensApi->create_token:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling TokensApi->create_token: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **token_create** | [**TokenCreate**](TokenCreate.md)|  | 

### Return type

[**TokenCreated**](TokenCreated.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**201** | Created. &#x60;token&#x60; is shown only now. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_token**
> Token get_token(token_id)

One credential.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.token import Token
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
    api_instance = kotobaya_client.TokensApi(api_client)
    token_id = '01931f3f-0000-7000-8000-0000000000b1' # str | `api_token.id`.

    try:
        # One credential.
        api_response = api_instance.get_token(token_id)
        print("The response of TokensApi->get_token:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling TokensApi->get_token: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **token_id** | **str**| &#x60;api_token.id&#x60;. | 

### Return type

[**Token**](Token.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The token (metadata only). |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_tokens**
> TokenList list_tokens(kind=kind, active=active)

List credentials of every kind (never the token strings or hashes).

Not paginated. Ordered by `created_at` descending, then `id`. Includes expired and revoked
rows; filter with `kind` and `active`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.token_kind import TokenKind
from kotobaya_client.models.token_list import TokenList
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
    api_instance = kotobaya_client.TokensApi(api_client)
    kind = kotobaya_client.TokenKind() # TokenKind | Only tokens of this kind. (optional)
    active = true # bool | `true` = only currently valid tokens; `false` = only expired or revoked; absent = all. (optional)

    try:
        # List credentials of every kind (never the token strings or hashes).
        api_response = api_instance.list_tokens(kind=kind, active=active)
        print("The response of TokensApi->list_tokens:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling TokensApi->list_tokens: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **kind** | [**TokenKind**](.md)| Only tokens of this kind. | [optional] 
 **active** | **bool**| &#x60;true&#x60; &#x3D; only currently valid tokens; &#x60;false&#x60; &#x3D; only expired or revoked; absent &#x3D; all. | [optional] 

### Return type

[**TokenList**](TokenList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Tokens. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **revoke_token**
> revoke_token(token_id)

Revoke a credential of any kind.

Sets `revoked_at`. Idempotent (`204` if already revoked or expired). Rows are kept for the
audit trail. A caller may revoke the token it is using; the response is still `204` and
the next request fails with `401`.


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
    api_instance = kotobaya_client.TokensApi(api_client)
    token_id = '01931f3f-0000-7000-8000-0000000000b1' # str | `api_token.id`.

    try:
        # Revoke a credential of any kind.
        api_instance.revoke_token(token_id)
    except Exception as e:
        print("Exception when calling TokensApi->revoke_token: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **token_id** | **str**| &#x60;api_token.id&#x60;. | 

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
**204** | Revoked. |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

