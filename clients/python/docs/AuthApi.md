# kotobaya_client.AuthApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**get_principal**](AuthApi.md#get_principal) | **GET** /auth/session | Who am I - the credential making this request.
[**login**](AuthApi.md#login) | **POST** /auth/login | Password login; sets the session cookie.
[**logout**](AuthApi.md#logout) | **POST** /auth/logout | Revoke the current session and clear the cookie.


# **get_principal**
> Principal get_principal()

Who am I - the credential making this request.

Works for every credential kind. Needs no particular scope, only a valid credential.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.principal import Principal
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
    api_instance = kotobaya_client.AuthApi(api_client)

    try:
        # Who am I - the credential making this request.
        api_response = api_instance.get_principal()
        print("The response of AuthApi->get_principal:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling AuthApi->get_principal: %s\n" % e)
```



### Parameters

This endpoint does not need any parameter.

### Return type

[**Principal**](Principal.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The calling credential. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **login**
> Principal login(login_request)

Password login; sets the session cookie.

Public. Verifies the password (and TOTP code when TOTP is enabled) against the hash and
secret the server reads from its environment (Kubernetes Secret; there is no user table).
On success inserts an `api_token` row of kind `session` (name `web session <ISO date>`,
all five scopes, `expires_at` = now + 30 days) and sets
`Set-Cookie: kotobaya_session=<token>; Path=/api; HttpOnly; Secure; SameSite=Strict; Max-Age=2592000`.
The token string is never returned in the body.

Edge cases:
- Wrong password, or wrong `totp_code`: `401 invalid_credentials` (same response for both).
- TOTP enabled and `totp_code` absent: `401 totp_required` (password is not checked first,
  so the response does not reveal whether the password was right).
- TOTP codes are 6 digits, 30 s step, accepted for the current and the previous step; a
  code is accepted once.
- More than 5 failures from one client address in 15 minutes: `429 rate_limited` with
  `Retry-After`, also for correct credentials, until the window passes.
- Already logged in: a new session is created; the old one stays valid until it expires
  or is logged out.


### Example


```python
import kotobaya_client
from kotobaya_client.models.login_request import LoginRequest
from kotobaya_client.models.principal import Principal
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)


# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.AuthApi(api_client)
    login_request = {"password":"correct horse battery staple"} # LoginRequest | 

    try:
        # Password login; sets the session cookie.
        api_response = api_instance.login(login_request)
        print("The response of AuthApi->login:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling AuthApi->login: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **login_request** | [**LoginRequest**](LoginRequest.md)|  | 

### Return type

[**Principal**](Principal.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Logged in. The session cookie is set. |  * Set-Cookie - The session cookie. <br>  |
**401** | Missing, unknown, expired or revoked credential; or wrong login or pairing data. |  -  |
**429** | Too many attempts. |  * Retry-After - Seconds until the next attempt is allowed. <br>  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **logout**
> logout()

Revoke the current session and clear the cookie.

Sets `revoked_at` on the calling session token and clears the cookie
(`Set-Cookie: kotobaya_session=; Path=/api; Max-Age=0`). Called with a bearer token of
kind `device` or `personal` it revokes nothing and returns `422 validation_failed`
(revoke those with `DELETE /tokens/{id}` or `DELETE /devices/{id}`).


### Example

* Api Key Authentication (sessionCookie):

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

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.AuthApi(api_client)

    try:
        # Revoke the current session and clear the cookie.
        api_instance.logout()
    except Exception as e:
        print("Exception when calling AuthApi->logout: %s\n" % e)
```



### Parameters

This endpoint does not need any parameter.

### Return type

void (empty response body)

### Authorization

[sessionCookie](../README.md#sessionCookie)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**204** | Logged out. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

