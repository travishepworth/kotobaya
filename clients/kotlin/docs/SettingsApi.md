# SettingsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**listSettings**](SettingsApi.md#listSettings) | **GET** /settings | All stored settings. |
| [**updateSettings**](SettingsApi.md#updateSettings) | **PATCH** /settings | Set one or more settings. |


<a id="listSettings"></a>
# **listSettings**
> SettingList listSettings()

All stored settings.

Returns one entry per &#x60;app.settings&#x60; row whose value is not JSON &#x60;null&#x60;, ordered by &#x60;key&#x60;. Keys never written are absent: their defaults live in the shared core, not on the server. Well-known keys are listed in &#x60;contracts/db/SCHEMA.md&#x60; section 6; unknown keys are stored and returned untouched. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = SettingsApi()
try {
    val result : SettingList = apiInstance.listSettings()
    println(result)
} catch (e: ClientException) {
    println("4xx response calling SettingsApi#listSettings")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling SettingsApi#listSettings")
    e.printStackTrace()
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

[**SettingList**](SettingList.md)

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

<a id="updateSettings"></a>
# **updateSettings**
> SettingList updateSettings(settingsPatch)

Set one or more settings.

Server emits one &#x60;settings.set&#x60; op per key in &#x60;values&#x60;, in ascending key order, all in one transaction. A JSON &#x60;null&#x60; value resets the key to its default (the row is kept with value &#x60;null&#x60; so the reset wins against older writes). Keys must match &#x60;^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$&#x60;. Values of well-known keys are validated against the types in SCHEMA.md section 6 (&#x60;422&#x60; with &#x60;pointer&#x60; &#x60;/values/&lt;key&gt;&#x60;); values of unknown keys are accepted as any JSON. An empty &#x60;values&#x60; object is &#x60;422&#x60;. The response is the full list as &#x60;GET /settings&#x60; returns it after the change. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = SettingsApi()
val settingsPatch : SettingsPatch = {"values":{"scheduler.leech_threshold":null}} // SettingsPatch | 
try {
    val result : SettingList = apiInstance.updateSettings(settingsPatch)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling SettingsApi#updateSettings")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling SettingsApi#updateSettings")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **settingsPatch** | [**SettingsPatch**](SettingsPatch.md)|  | |

### Return type

[**SettingList**](SettingList.md)

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

