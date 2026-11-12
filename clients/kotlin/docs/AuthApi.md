# AuthApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**getPrincipal**](AuthApi.md#getPrincipal) | **GET** /auth/session | Who am I - the credential making this request. |
| [**login**](AuthApi.md#login) | **POST** /auth/login | Password login; sets the session cookie. |
| [**logout**](AuthApi.md#logout) | **POST** /auth/logout | Revoke the current session and clear the cookie. |


<a id="getPrincipal"></a>
# **getPrincipal**
> Principal getPrincipal()

Who am I - the credential making this request.

Works for every credential kind. Needs no particular scope, only a valid credential.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = AuthApi()
try {
    val result : Principal = apiInstance.getPrincipal()
    println(result)
} catch (e: ClientException) {
    println("4xx response calling AuthApi#getPrincipal")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling AuthApi#getPrincipal")
    e.printStackTrace()
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

[**Principal**](Principal.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="login"></a>
# **login**
> Principal login(loginRequest)

Password login; sets the session cookie.

Public. Verifies the password (and TOTP code when TOTP is enabled) against the hash and secret the server reads from its environment (Kubernetes Secret; there is no user table). On success inserts an &#x60;api_token&#x60; row of kind &#x60;session&#x60; (name &#x60;web session &lt;ISO date&gt;&#x60;, all five scopes, &#x60;expires_at&#x60; &#x3D; now + 30 days) and sets &#x60;Set-Cookie: kotobaya_session&#x3D;&lt;token&gt;; Path&#x3D;/api; HttpOnly; Secure; SameSite&#x3D;Strict; Max-Age&#x3D;2592000&#x60;. The token string is never returned in the body.  Edge cases: - Wrong password, or wrong &#x60;totp_code&#x60;: &#x60;401 invalid_credentials&#x60; (same response for both). - TOTP enabled and &#x60;totp_code&#x60; absent: &#x60;401 totp_required&#x60; (password is not checked first,   so the response does not reveal whether the password was right). - TOTP codes are 6 digits, 30 s step, accepted for the current and the previous step; a   code is accepted once. - More than 5 failures from one client address in 15 minutes: &#x60;429 rate_limited&#x60; with   &#x60;Retry-After&#x60;, also for correct credentials, until the window passes. - Already logged in: a new session is created; the old one stays valid until it expires   or is logged out. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = AuthApi()
val loginRequest : LoginRequest = {"password":"correct horse battery staple"} // LoginRequest | 
try {
    val result : Principal = apiInstance.login(loginRequest)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling AuthApi#login")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling AuthApi#login")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **loginRequest** | [**LoginRequest**](LoginRequest.md)|  | |

### Return type

[**Principal**](Principal.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

<a id="logout"></a>
# **logout**
> logout()

Revoke the current session and clear the cookie.

Sets &#x60;revoked_at&#x60; on the calling session token and clears the cookie (&#x60;Set-Cookie: kotobaya_session&#x3D;; Path&#x3D;/api; Max-Age&#x3D;0&#x60;). Called with a bearer token of kind &#x60;device&#x60; or &#x60;personal&#x60; it revokes nothing and returns &#x60;422 validation_failed&#x60; (revoke those with &#x60;DELETE /tokens/{id}&#x60; or &#x60;DELETE /devices/{id}&#x60;). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = AuthApi()
try {
    apiInstance.logout()
} catch (e: ClientException) {
    println("4xx response calling AuthApi#logout")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling AuthApi#logout")
    e.printStackTrace()
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

null (empty response body)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/problem+json

