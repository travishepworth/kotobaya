# PresetsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**createPreset**](PresetsApi.md#createPreset) | **POST** /presets | Create a preset. |
| [**deletePreset**](PresetsApi.md#deletePreset) | **DELETE** /presets/{preset_id} | Delete a preset. |
| [**getPreset**](PresetsApi.md#getPreset) | **GET** /presets/{preset_id} | One preset. |
| [**listPresets**](PresetsApi.md#listPresets) | **GET** /presets | List scheduling presets. |
| [**optimizePreset**](PresetsApi.md#optimizePreset) | **POST** /presets/{preset_id}/optimize | Queue an FSRS optimizer run for this preset. |
| [**updatePreset**](PresetsApi.md#updatePreset) | **PATCH** /presets/{preset_id} | Change preset options. |


<a id="createPreset"></a>
# **createPreset**
> Preset createPreset(presetCreate)

Create a preset.

Server emits &#x60;fsrs_params.created&#x60;. Omitted properties take the column defaults. Weights cannot be set through the API; they are published by the optimizer job. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = PresetsApi()
val presetCreate : PresetCreate = {"desired_retention":0.85,"learning_steps_s":[60,600,3600],"name":"Kanji"} // PresetCreate | 
try {
    val result : Preset = apiInstance.createPreset(presetCreate)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling PresetsApi#createPreset")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling PresetsApi#createPreset")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **presetCreate** | [**PresetCreate**](PresetCreate.md)|  | |

### Return type

[**Preset**](Preset.md)

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

<a id="deletePreset"></a>
# **deletePreset**
> deletePreset(presetId)

Delete a preset.

Server emits &#x60;fsrs_params.deleted&#x60;. Cards pointing at a deleted preset are scheduled with the Default preset (their &#x60;preset_id&#x60; is not rewritten). The Default preset (&#x60;00000000-0000-7000-8000-000000000010&#x60;) cannot be deleted: &#x60;409 builtin_immutable&#x60;. Already deleted: &#x60;204&#x60;, no op. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = PresetsApi()
val presetId : kotlin.String = 00000000-0000-7000-8000-000000000010 // kotlin.String | Preset id (`fsrs_params.id`).
try {
    apiInstance.deletePreset(presetId)
} catch (e: ClientException) {
    println("4xx response calling PresetsApi#deletePreset")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling PresetsApi#deletePreset")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **presetId** | **kotlin.String**| Preset id (&#x60;fsrs_params.id&#x60;). | |

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

<a id="getPreset"></a>
# **getPreset**
> Preset getPreset(presetId, includeDeleted)

One preset.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = PresetsApi()
val presetId : kotlin.String = 00000000-0000-7000-8000-000000000010 // kotlin.String | Preset id (`fsrs_params.id`).
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
try {
    val result : Preset = apiInstance.getPreset(presetId, includeDeleted)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling PresetsApi#getPreset")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling PresetsApi#getPreset")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **presetId** | **kotlin.String**| Preset id (&#x60;fsrs_params.id&#x60;). | |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |

### Return type

[**Preset**](Preset.md)

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

<a id="listPresets"></a>
# **listPresets**
> PresetList listPresets(includeDeleted)

List scheduling presets.

Not paginated. Ordered by &#x60;created_at&#x60;, then &#x60;id&#x60; (the Default preset first).

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = PresetsApi()
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
try {
    val result : PresetList = apiInstance.listPresets(includeDeleted)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling PresetsApi#listPresets")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling PresetsApi#listPresets")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |

### Return type

[**PresetList**](PresetList.md)

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

<a id="optimizePreset"></a>
# **optimizePreset**
> Job optimizePreset(presetId)

Queue an FSRS optimizer run for this preset.

Enqueues a job of kind &#x60;fsrs-optimize&#x60; (payload &#x60;JobPayloadFsrsOptimize&#x60;, dedupe key &#x60;fsrs-optimize:&lt;preset id&gt;&#x60;). If a queued or running job with that key exists it is returned with &#x60;200&#x60; instead of creating another. When the job succeeds the worker publishes the weights: the server emits &#x60;fsrs_params.published&#x60; (not this request). The weekly schedule enqueues the same job for every live preset.  **Execution (C-16).** The worker trains on the preset&#39;s reviews and compares, on a held-out set of reviews, the log loss of the preset&#39;s current weights with that of the new ones. Only when the new weights are strictly better (and within the weight bounds) does it hand them to the server through the server-internal job kind &#x60;fsrs-params-publish&#x60; (rules for internal jobs: &#x60;Job&#x60;); the server checks the same condition again and mints one &#x60;fsrs_params.published&#x60; with &#x60;weights_version&#x60; + 1. Otherwise the job still ends &#x60;succeeded&#x60;, with &#x60;published: false&#x60; and a &#x60;reason&#x60;, and the preset is unchanged. A retried or repeated job never publishes the same result twice. Normative protocol: &#x60;contracts/db/FSRS_PUBLISH.md&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = PresetsApi()
val presetId : kotlin.String = 00000000-0000-7000-8000-000000000010 // kotlin.String | Preset id (`fsrs_params.id`).
try {
    val result : Job = apiInstance.optimizePreset(presetId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling PresetsApi#optimizePreset")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling PresetsApi#optimizePreset")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **presetId** | **kotlin.String**| Preset id (&#x60;fsrs_params.id&#x60;). | |

### Return type

[**Job**](Job.md)

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

<a id="updatePreset"></a>
# **updatePreset**
> Preset updatePreset(presetId, presetPatch)

Change preset options.

Server emits one &#x60;fsrs_params.updated&#x60; carrying the registers present in the body. Changing any option makes every card cache on the preset stale; replicas recompute (SCHEMA.md 4.2). Patching a deleted preset: &#x60;410 gone&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = PresetsApi()
val presetId : kotlin.String = 00000000-0000-7000-8000-000000000010 // kotlin.String | Preset id (`fsrs_params.id`).
val presetPatch : PresetPatch = {"easy_days":[1,1,1,1,1,1,0.5]} // PresetPatch | 
try {
    val result : Preset = apiInstance.updatePreset(presetId, presetPatch)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling PresetsApi#updatePreset")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling PresetsApi#updatePreset")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **presetId** | **kotlin.String**| Preset id (&#x60;fsrs_params.id&#x60;). | |
| **presetPatch** | [**PresetPatch**](PresetPatch.md)|  | |

### Return type

[**Preset**](Preset.md)

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

