# JobsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**cancelJob**](JobsApi.md#cancelJob) | **POST** /jobs/{job_id}/cancel | Cancel a queued or running job. |
| [**getJob**](JobsApi.md#getJob) | **GET** /jobs/{job_id} | One job with progress, result or error. |
| [**listJobs**](JobsApi.md#listJobs) | **GET** /jobs | List background jobs (imports, parses, optimizer runs, ...). |
| [**resolveSpeech**](JobsApi.md#resolveSpeech) | **POST** /tts/resolve | Look up generated speech for texts or notes, generating what is missing. |
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

<a id="resolveSpeech"></a>
# **resolveSpeech**
> SpeechResolveResult resolveSpeech(speechResolveRequest)

Look up generated speech for texts or notes, generating what is missing.

ADR-047 (C-29). The one way a client turns \&quot;this word should have audio\&quot; into a blob hash. Answers at once from the system table &#x60;tts_audio&#x60;; whatever is not generated yet is queued as a &#x60;tts&#x60; job and reported &#x60;pending&#x60;, and the caller asks again. It changes no learning data and emits no op: generated audio is derived data (SCHEMA.md 6.6).  **Items.** 1 to 200, each **exactly one** of: - &#x60;text&#x60; (with optional &#x60;voice&#x60;): a pair. &#x60;voice&#x60; absent or null &#x3D; the effective voice   (setting &#x60;tts.voice&#x60; when valid, else &#x60;\&quot;3\&quot;&#x60;). - &#x60;note_id&#x60;: the word audio of that note as the core &#x60;WordAudioResolver&#x60; decides it (the   note&#39;s own &#x60;[sound:]&#x60; first, else the pair of its speech text and the effective voice).   For clients that show a card outside a review queue.  An item with both, or neither, or &#x60;voice&#x60; together with &#x60;note_id&#x60;, is &#x60;422&#x60; (pointer &#x60;/items/&lt;i&gt;&#x60;); nothing is enqueued for any item of a rejected request. Duplicate items are allowed and answered alike.  **Result.** &#x60;items&#x60; has one &#x60;SpeechResolution&#x60; per request item, in request order:  | &#x60;status&#x60; | Meaning | &#x60;blob_hash&#x60; | Enqueues | |---|---|---|---| | &#x60;ready&#x60; | Generated (or the note&#39;s own audio): play &#x60;blob_hash&#x60; through &#x60;GET /blobs/{hash}&#x60;. | hash | no | | &#x60;pending&#x60; | Not generated yet. A &#x60;tts&#x60; job for the pair is queued or running, or will be queued by a later call (see \&quot;Backlog\&quot;). Ask again after &#x60;retry_after_ms&#x60;. | null | yes, unless one exists | | &#x60;failed&#x60; | The last attempt failed less than 600 s ago (VOICEVOX down, unknown voice). No new job until then; ask again after &#x60;retry_after_ms&#x60;. | null | no | | &#x60;none&#x60; | Only for &#x60;note_id&#x60;: no such live note (unknown, deleted, merged away), not a &#x60;vocab&#x60; note, or no speakable text. Final for this note content. | null | no |  **Urgent.** With &#x60;urgent: true&#x60; (at most 20 items, else &#x60;422&#x60; pointer &#x60;/urgent&#x60;) the jobs this call needs run before every non-urgent job (SCHEMA.md 6.6 \&quot;Urgent\&quot;). The review screen uses it for the card on screen; prefetch never does.  **Backlog.** A non-urgent call enqueues only while fewer than 300 &#x60;tts&#x60; jobs are queued or running; beyond that its missing pairs are still &#x60;pending&#x60;, with &#x60;retry_after_ms&#x60; 60000, and are enqueued by a later call or by the server&#39;s sweep.  **Idempotent and order-free.** Repeating a call, or two callers asking for the same pair at once, never produces two jobs for one pair (job dedupe key &#x60;tts:&lt;sha256 of voice + NUL + text&gt;&#x60;) and never two different answers once it is &#x60;ready&#x60;, except after a re-synthesis, which answers the newer blob; the older blob stays valid.  **Client rules.** - Review screen: for the item on screen whose &#x60;word_audio&#x60; has &#x60;source: tts&#x60; and   &#x60;blob_hash: null&#x60;, call with that &#x60;text&#x60; and &#x60;voice&#x60;, &#x60;urgent: true&#x60;, when the card is   first shown (not at reveal). On &#x60;pending&#x60; ask again after &#x60;retry_after_ms&#x60;, then double   the wait, at most 5 calls per showing; on &#x60;failed&#x60;, &#x60;none&#x60;, a network error or &#x60;5xx&#x60;   stop for this showing. A card never waits for audio. - Prefetch (Android): SCHEMA.md 6.6 \&quot;Device\&quot;. - Never write the answer into a note. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = JobsApi()
val speechResolveRequest : SpeechResolveRequest = {"items":[{"note_id":"01931f40-1a2b-7c3d-8e4f-000000000a01"},{"note_id":"01931f40-1a2b-7c3d-8e4f-000000000a02"}]} // SpeechResolveRequest | 
try {
    val result : SpeechResolveResult = apiInstance.resolveSpeech(speechResolveRequest)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling JobsApi#resolveSpeech")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling JobsApi#resolveSpeech")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **speechResolveRequest** | [**SpeechResolveRequest**](SpeechResolveRequest.md)|  | |

### Return type

[**SpeechResolveResult**](SpeechResolveResult.md)

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

<a id="synthesizeSpeech"></a>
# **synthesizeSpeech**
> Job synthesizeSpeech(ttsRequest)

Synthesize Japanese speech (VOICEVOX) into an audio blob.

Enqueues a job of kind &#x60;tts&#x60; (dedupe key &#x60;tts:&lt;sha256 of voice + NUL + text&gt;&#x60;); an equivalent queued or running job is returned with &#x60;200&#x60;. On success &#x60;job.result.blob_hash&#x60; names an audio blob (kind &#x60;audio&#x60;, origin &#x60;tts&#x60;). This call changes no note. A caller that wants the sound stored **in** a note puts &#x60;[sound:&lt;hash&gt;]&#x60; into a field with &#x60;PATCH /notes/{id}&#x60;; word audio of vocab cards needs none of that (ADR-047: see &#x60;QueueItem.word_audio&#x60; and &#x60;resolveSpeech&#x60;).  &#x60;voice&#x60; defaults to the effective voice (setting &#x60;tts.voice&#x60; when it is a string of 1-9 decimal digits, else &#x60;\&quot;3\&quot;&#x60;; SCHEMA.md 6.6); the server writes the resolved voice into the job payload. The worker records every success in the system table &#x60;tts_audio&#x60; (&#x60;(voice, text)&#x60; to blob), so a later request for the same pair, by this operation or by &#x60;resolveSpeech&#x60;, does not call VOICEVOX again. A &#x60;voice&#x60; that is not decimal digits is accepted here for compatibility and fails the job. 

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

