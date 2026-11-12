# ParsingApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**deleteParseOverride**](ParsingApi.md#deleteParseOverride) | **DELETE** /parse-overrides/{override_id} | Delete a tokenisation correction (permanent). |
| [**listParseOverrides**](ParsingApi.md#listParseOverrides) | **GET** /parse-overrides | List your tokenisation corrections. |
| [**parseText**](ParsingApi.md#parseText) | **POST** /parse | Parse text - tokens, matched entries and their known status. |
| [**putParseOverride**](ParsingApi.md#putParseOverride) | **PUT** /parse-overrides/{override_id} | Create or replace a tokenisation correction. |


<a id="deleteParseOverride"></a>
# **deleteParseOverride**
> deleteParseOverride(overrideId)

Delete a tokenisation correction (permanent).

Server emits &#x60;parse_override.deleted&#x60;. Already deleted - &#x60;204&#x60;, no op. Unknown id - &#x60;404&#x60;.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = ParsingApi()
val overrideId : kotlin.String = 01931f40-6000-7000-8000-000000000801 // kotlin.String | Override id (client-generated UUIDv7).
try {
    apiInstance.deleteParseOverride(overrideId)
} catch (e: ClientException) {
    println("4xx response calling ParsingApi#deleteParseOverride")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ParsingApi#deleteParseOverride")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **overrideId** | **kotlin.String**| Override id (client-generated UUIDv7). | |

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

<a id="listParseOverrides"></a>
# **listParseOverrides**
> ParseOverridePage listParseOverrides(mediaId, scope, includeDeleted, limit, cursor)

List your tokenisation corrections.

Ordered by &#x60;updated_at&#x60; descending, then &#x60;id&#x60;.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = ParsingApi()
val mediaId : kotlin.String = 01931f40-3000-7000-8000-000000000e01 // kotlin.String | Only overrides scoped to this media (including sentence-scoped ones inside it).
val scope : kotlin.String = global // kotlin.String | Only overrides of this scope.
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : ParseOverridePage = apiInstance.listParseOverrides(mediaId, scope, includeDeleted, limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling ParsingApi#listParseOverrides")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ParsingApi#listParseOverrides")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **mediaId** | **kotlin.String**| Only overrides scoped to this media (including sentence-scoped ones inside it). | [optional] |
| **scope** | **kotlin.String**| Only overrides of this scope. | [optional] [enum: global, media, sentence] |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**ParseOverridePage**](ParseOverridePage.md)

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

<a id="parseText"></a>
# **parseText**
> ParseResult parseText(parseRequest)

Parse text - tokens, matched entries and their known status.

Runs the same pipeline as media parsing (normalise, Sudachi, deinflect, match JMdict, apply global parse overrides) synchronously and stores nothing. The text is split into sentences; token offsets are relative to each sentence&#39;s &#x60;text&#x60;. &#x60;entries&#x60; holds one summary (with your &#x60;status&#x60; and &#x60;note_id&#x60;) per distinct &#x60;seq&#x60; among the tokens. Text of 1 to 20,000 characters; longer is &#x60;413 payload_too_large&#x60;. If the parser (worker) is unreachable: &#x60;503 unavailable&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = ParsingApi()
val parseRequest : ParseRequest = {"text":"パンを食べる。"} // ParseRequest | 
try {
    val result : ParseResult = apiInstance.parseText(parseRequest)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling ParsingApi#parseText")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ParsingApi#parseText")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **parseRequest** | [**ParseRequest**](ParseRequest.md)|  | |

### Return type

[**ParseResult**](ParseResult.md)

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

<a id="putParseOverride"></a>
# **putParseOverride**
> ParseOverride putParseOverride(overrideId, parseOverridePut)

Create or replace a tokenisation correction.

Server emits &#x60;parse_override.put&#x60; (whole-row last-writer-wins). The token surfaces must concatenate exactly to &#x60;match_text&#x60; (&#x60;422&#x60; otherwise). &#x60;sentence_id&#x60; requires &#x60;media_id&#x60;. Scope references are soft and not checked. Putting a deleted id is &#x60;410 gone&#x60; (deletes are permanent; use a new id). Overrides take effect at the next parse of the affected media (&#x60;POST /media/{id}/reparse&#x60;) and immediately in &#x60;POST /parse&#x60; (global ones). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = ParsingApi()
val overrideId : kotlin.String = 01931f40-6000-7000-8000-000000000801 // kotlin.String | Override id (client-generated UUIDv7).
val parseOverridePut : ParseOverridePut = {"match_text":"食べかけ","media_id":"01931f40-3000-7000-8000-000000000e01","note":"split compound","tokens":[{"lemma":"食べる","seq":1358280,"surface":"食べ"},{"lemma":"かける","seq":1207610,"surface":"かけ"}]} // ParseOverridePut | 
try {
    val result : ParseOverride = apiInstance.putParseOverride(overrideId, parseOverridePut)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling ParsingApi#putParseOverride")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ParsingApi#putParseOverride")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **overrideId** | **kotlin.String**| Override id (client-generated UUIDv7). | |
| **parseOverridePut** | [**ParseOverridePut**](ParseOverridePut.md)|  | |

### Return type

[**ParseOverride**](ParseOverride.md)

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

