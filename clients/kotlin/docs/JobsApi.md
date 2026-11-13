# JobsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**cancelJob**](JobsApi.md#cancelJob) | **POST** /jobs/{job_id}/cancel | Cancel a queued or running job. |
| [**getJob**](JobsApi.md#getJob) | **GET** /jobs/{job_id} | One job with progress, result or error. |
| [**listJobs**](JobsApi.md#listJobs) | **GET** /jobs | List background jobs (imports, parses, optimizer runs, ...). |
| [**synthesizeSpeech**](JobsApi.md#synthesizeSpeech) | **POST** /tts | Synthesize Japanese speech (VOICEVOX) into an audio blob. |


<a id="cancelJob"></a>
# **cancelJob**
> Job cancelJob(jobId)

Cancel a queued or running job.

Sets &#x60;status &#x3D; cancelled&#x60;, &#x60;finished_at &#x3D; now&#x60;. A running job&#39;s worker notices at its next heartbeat (within 30 s) and stops; content it already published stays published. Already cancelled: &#x60;200&#x60;, unchanged. Succeeded or failed: &#x60;409 job_not_cancellable&#x60;. A server-internal job&#39;s id (C-9, see &#x60;Job&#x60;) answers &#x60;404 not_found&#x60;; cancel its parent instead, which also stops the child. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = JobsApi()
val jobId : kotlin.String = 01931f42-0000-7000-8000-000000000b01 // kotlin.String | Job id.
try {
    val result : Job = apiInstance.cancelJob(jobId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling JobsApi#cancelJob")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling JobsApi#cancelJob")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **jobId** | **kotlin.String**| Job id. | |

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

<a id="getJob"></a>
# **getJob**
> Job getJob(jobId)

One job with progress, result or error.

The id of a server-internal job (kind not in &#x60;JobKind&#x60;, C-9, see &#x60;Job&#x60;) answers &#x60;404 not_found&#x60;, as if it did not exist.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = JobsApi()
val jobId : kotlin.String = 01931f42-0000-7000-8000-000000000b01 // kotlin.String | Job id.
try {
    val result : Job = apiInstance.getJob(jobId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling JobsApi#getJob")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling JobsApi#getJob")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **jobId** | **kotlin.String**| Job id. | |

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

<a id="listJobs"></a>
# **listJobs**
> JobPage listJobs(kind, status, limit, cursor)

List background jobs (imports, parses, optimizer runs, ...).

Ordered by &#x60;created_at&#x60; descending, then &#x60;id&#x60; descending. Jobs are kept for at least 30 days after finishing. Server-internal jobs (kind not in &#x60;JobKind&#x60;, C-9, see &#x60;Job&#x60;) are never listed.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = JobsApi()
val kind : JobKind = media-import // JobKind | Only jobs of this kind.
val status : kotlin.collections.Set<JobStatus> = ["queued","running"] // kotlin.collections.Set<JobStatus> | Only jobs in one of these states (comma-separated).
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : JobPage = apiInstance.listJobs(kind, status, limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling JobsApi#listJobs")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling JobsApi#listJobs")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **kind** | [**JobKind**](.md)| Only jobs of this kind. | [optional] [enum: dict-refresh, media-import, media-parse, jpdb-deck-import, jpdb-history-import, fsrs-optimize, tts, deck-build, grammar-detect] |
| **status** | [**kotlin.collections.Set&lt;JobStatus&gt;**](JobStatus.md)| Only jobs in one of these states (comma-separated). | [optional] |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**JobPage**](JobPage.md)

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

<a id="synthesizeSpeech"></a>
# **synthesizeSpeech**
> Job synthesizeSpeech(ttsRequest)

Synthesize Japanese speech (VOICEVOX) into an audio blob.

Enqueues a job of kind &#x60;tts&#x60; (dedupe key &#x60;tts:&lt;sha256 of voice + NUL + text&gt;&#x60;); an equivalent queued or running job is returned with &#x60;200&#x60;. On success &#x60;job.result.blob_hash&#x60; names an audio blob (kind &#x60;audio&#x60;, origin &#x60;tts&#x60;). The caller then puts &#x60;[sound:&lt;hash&gt;]&#x60; into a note field with &#x60;PATCH /notes/{id}&#x60;; this call changes no note. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = JobsApi()
val ttsRequest : TtsRequest = {"text":"パンを食べる"} // TtsRequest | 
try {
    val result : Job = apiInstance.synthesizeSpeech(ttsRequest)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling JobsApi#synthesizeSpeech")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling JobsApi#synthesizeSpeech")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **ttsRequest** | [**TtsRequest**](TtsRequest.md)|  | |

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

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

