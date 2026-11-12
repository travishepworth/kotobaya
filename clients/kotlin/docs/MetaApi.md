# MetaApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**getHealth**](MetaApi.md#getHealth) | **GET** /health | Liveness and readiness probe. |
| [**getMeta**](MetaApi.md#getMeta) | **GET** /meta | Server versions, limits and sync head. |


<a id="getHealth"></a>
# **getHealth**
> Health getHealth()

Liveness and readiness probe.

Public. &#x60;200&#x60; when the API can reach Postgres, &#x60;503&#x60; (problem &#x60;unavailable&#x60;) otherwise. Does not check the object store or the worker. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MetaApi()
try {
    val result : Health = apiInstance.getHealth()
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MetaApi#getHealth")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MetaApi#getHealth")
    e.printStackTrace()
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

[**Health**](Health.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="getMeta"></a>
# **getMeta**
> Meta getMeta()

Server versions, limits and sync head.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MetaApi()
try {
    val result : Meta = apiInstance.getMeta()
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MetaApi#getMeta")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MetaApi#getMeta")
    e.printStackTrace()
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

[**Meta**](Meta.md)

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

