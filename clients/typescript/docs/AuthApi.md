# AuthApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getPrincipal**](AuthApi.md#getprincipal) | **GET** /auth/session | Who am I - the credential making this request. |
| [**login**](AuthApi.md#loginoperation) | **POST** /auth/login | Password login; sets the session cookie. |
| [**logout**](AuthApi.md#logout) | **POST** /auth/logout | Revoke the current session and clear the cookie. |



## getPrincipal

> Principal getPrincipal()

Who am I - the credential making this request.

Works for every credential kind. Needs no particular scope, only a valid credential.

### Example

```ts
import {
  Configuration,
  AuthApi,
} from '@kotobaya/api-client';
import type { GetPrincipalRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new AuthApi(config);

  try {
    const data = await api.getPrincipal();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**Principal**](Principal.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The calling credential. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## login

> Principal login(loginRequest)

Password login; sets the session cookie.

Public. Verifies the password (and TOTP code when TOTP is enabled) against the hash and secret the server reads from its environment (Kubernetes Secret; there is no user table). On success inserts an &#x60;api_token&#x60; row of kind &#x60;session&#x60; (name &#x60;web session &lt;ISO date&gt;&#x60;, all five scopes, &#x60;expires_at&#x60; &#x3D; now + 30 days) and sets &#x60;Set-Cookie: kotobaya_session&#x3D;&lt;token&gt;; Path&#x3D;/api; HttpOnly; Secure; SameSite&#x3D;Strict; Max-Age&#x3D;2592000&#x60;. The token string is never returned in the body.  Edge cases: - Wrong password, or wrong &#x60;totp_code&#x60;: &#x60;401 invalid_credentials&#x60; (same response for both). - TOTP enabled and &#x60;totp_code&#x60; absent: &#x60;401 totp_required&#x60; (password is not checked first,   so the response does not reveal whether the password was right). - TOTP codes are 6 digits, 30 s step, accepted for the current and the previous step; a   code is accepted once. - More than 5 failures from one client address in 15 minutes: &#x60;429 rate_limited&#x60; with   &#x60;Retry-After&#x60;, also for correct credentials, until the window passes. - Already logged in: a new session is created; the old one stays valid until it expires   or is logged out. 

### Example

```ts
import {
  Configuration,
  AuthApi,
} from '@kotobaya/api-client';
import type { LoginOperationRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const api = new AuthApi();

  const body = {
    // LoginRequest
    loginRequest: {"password":"correct horse battery staple"},
  } satisfies LoginOperationRequest;

  try {
    const data = await api.login(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **loginRequest** | [LoginRequest](LoginRequest.md) |  | |

### Return type

[**Principal**](Principal.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Logged in. The session cookie is set. |  * Set-Cookie - The session cookie. <br>  |
| **401** | Missing, unknown, expired or revoked credential; or wrong login or pairing data. |  -  |
| **429** | Too many attempts. |  * Retry-After - Seconds until the next attempt is allowed. <br>  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## logout

> logout()

Revoke the current session and clear the cookie.

Sets &#x60;revoked_at&#x60; on the calling session token and clears the cookie (&#x60;Set-Cookie: kotobaya_session&#x3D;; Path&#x3D;/api; Max-Age&#x3D;0&#x60;). Called with a bearer token of kind &#x60;device&#x60; or &#x60;personal&#x60; it revokes nothing and returns &#x60;422 validation_failed&#x60; (revoke those with &#x60;DELETE /tokens/{id}&#x60; or &#x60;DELETE /devices/{id}&#x60;). 

### Example

```ts
import {
  Configuration,
  AuthApi,
} from '@kotobaya/api-client';
import type { LogoutRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
  });
  const api = new AuthApi(config);

  try {
    const data = await api.logout();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

`void` (Empty response body)

### Authorization

[sessionCookie](../README.md#sessionCookie)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Logged out. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

