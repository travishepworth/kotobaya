# TokensApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**createToken**](TokensApi.md#createToken) | **POST** /tokens | Create a personal access token. |
| [**getToken**](TokensApi.md#getToken) | **GET** /tokens/{token_id} | One credential. |
| [**listTokens**](TokensApi.md#listTokens) | **GET** /tokens | List credentials of every kind (never the token strings or hashes). |
| [**revokeToken**](TokensApi.md#revokeToken) | **DELETE** /tokens/{token_id} | Revoke a credential of any kind. |


<a id="createToken"></a>
# **createToken**
> TokenCreated createToken(tokenCreate)

Create a personal access token.

Returns the token string once. &#x60;scopes&#x60; must be non-empty, without duplicates. &#x60;expires_at&#x60;, when given, must be in the future. With &#x60;with_device: true&#x60; the server also creates a &#x60;device&#x60; row of kind &#x60;script&#x60; (server-generated id, same name) and binds the token to it, which allows the token to use &#x60;POST /sync/push&#x60;; revoking the token does not revoke that device, revoking the device revokes the token. Names need not be unique. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = TokensApi()
val tokenCreate : TokenCreate = {"name":"nightly-import","scopes":["read","import"]} // TokenCreate | 
try {
    val result : TokenCreated = apiInstance.createToken(tokenCreate)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling TokensApi#createToken")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling TokensApi#createToken")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **tokenCreate** | [**TokenCreate**](TokenCreate.md)|  | |

### Return type

[**TokenCreated**](TokenCreated.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

<a id="getToken"></a>
# **getToken**
> Token getToken(tokenId)

One credential.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = TokensApi()
val tokenId : kotlin.String = 01931f3f-0000-7000-8000-0000000000b1 // kotlin.String | `api_token.id`.
try {
    val result : Token = apiInstance.getToken(tokenId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling TokensApi#getToken")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling TokensApi#getToken")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **tokenId** | **kotlin.String**| &#x60;api_token.id&#x60;. | |

### Return type

[**Token**](Token.md)

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

<a id="listTokens"></a>
# **listTokens**
> TokenList listTokens(kind, active)

List credentials of every kind (never the token strings or hashes).

Not paginated. Ordered by &#x60;created_at&#x60; descending, then &#x60;id&#x60;. Includes expired and revoked rows; filter with &#x60;kind&#x60; and &#x60;active&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = TokensApi()
val kind : TokenKind = personal // TokenKind | Only tokens of this kind.
val active : kotlin.Boolean = true // kotlin.Boolean | `true` = only currently valid tokens; `false` = only expired or revoked; absent = all.
try {
    val result : TokenList = apiInstance.listTokens(kind, active)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling TokensApi#listTokens")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling TokensApi#listTokens")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **kind** | [**TokenKind**](.md)| Only tokens of this kind. | [optional] [enum: session, device, personal] |
| **active** | **kotlin.Boolean**| &#x60;true&#x60; &#x3D; only currently valid tokens; &#x60;false&#x60; &#x3D; only expired or revoked; absent &#x3D; all. | [optional] |

### Return type

[**TokenList**](TokenList.md)

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

<a id="revokeToken"></a>
# **revokeToken**
> revokeToken(tokenId)

Revoke a credential of any kind.

Sets &#x60;revoked_at&#x60;. Idempotent (&#x60;204&#x60; if already revoked or expired). Rows are kept for the audit trail. A caller may revoke the token it is using; the response is still &#x60;204&#x60; and the next request fails with &#x60;401&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = TokensApi()
val tokenId : kotlin.String = 01931f3f-0000-7000-8000-0000000000b1 // kotlin.String | `api_token.id`.
try {
    apiInstance.revokeToken(tokenId)
} catch (e: ClientException) {
    println("4xx response calling TokensApi#revokeToken")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling TokensApi#revokeToken")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **tokenId** | **kotlin.String**| &#x60;api_token.id&#x60;. | |

### Return type

null (empty response body)

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
 - **Accept**: application/problem+json

