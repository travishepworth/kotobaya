# JobsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**cancelJob**](JobsApi.md#canceljob) | **POST** /jobs/{job_id}/cancel | Cancel a queued or running job. |
| [**getJob**](JobsApi.md#getjob) | **GET** /jobs/{job_id} | One job with progress, result or error. |
| [**listJobs**](JobsApi.md#listjobs) | **GET** /jobs | List background jobs (imports, parses, optimizer runs, ...). |
| [**synthesizeSpeech**](JobsApi.md#synthesizespeech) | **POST** /tts | Synthesize Japanese speech (VOICEVOX) into an audio blob. |



## cancelJob

> Job cancelJob(jobId)

Cancel a queued or running job.

Sets &#x60;status &#x3D; cancelled&#x60;, &#x60;finished_at &#x3D; now&#x60;. A running job\&#39;s worker notices at its next heartbeat (within 30 s) and stops; content it already published stays published. Already cancelled: &#x60;200&#x60;, unchanged. Succeeded or failed: &#x60;409 job_not_cancellable&#x60;. A server-internal job\&#39;s id (C-9, see &#x60;Job&#x60;) answers &#x60;404 not_found&#x60;; cancel its parent instead, which also stops the child. 

### Example

```ts
import {
  Configuration,
  JobsApi,
} from '@kotobaya/api-client';
import type { CancelJobRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new JobsApi(config);

  const body = {
    // string | Job id.
    jobId: 01931f42-0000-7000-8000-000000000b01,
  } satisfies CancelJobRequest;

  try {
    const data = await api.cancelJob(body);
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
| **jobId** | `string` | Job id. | [Defaults to `undefined`] |

### Return type

[**Job**](Job.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The cancelled job. |  -  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getJob

> Job getJob(jobId)

One job with progress, result or error.

The id of a server-internal job (kind not in &#x60;JobKind&#x60;, C-9, see &#x60;Job&#x60;) answers &#x60;404 not_found&#x60;, as if it did not exist.

### Example

```ts
import {
  Configuration,
  JobsApi,
} from '@kotobaya/api-client';
import type { GetJobRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new JobsApi(config);

  const body = {
    // string | Job id.
    jobId: 01931f42-0000-7000-8000-000000000b01,
  } satisfies GetJobRequest;

  try {
    const data = await api.getJob(body);
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
| **jobId** | `string` | Job id. | [Defaults to `undefined`] |

### Return type

[**Job**](Job.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The job. |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listJobs

> JobPage listJobs(kind, status, limit, cursor)

List background jobs (imports, parses, optimizer runs, ...).

Ordered by &#x60;created_at&#x60; descending, then &#x60;id&#x60; descending. Jobs are kept for at least 30 days after finishing. Server-internal jobs (kind not in &#x60;JobKind&#x60;, C-9, see &#x60;Job&#x60;) are never listed.

### Example

```ts
import {
  Configuration,
  JobsApi,
} from '@kotobaya/api-client';
import type { ListJobsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new JobsApi(config);

  const body = {
    // JobKind | Only jobs of this kind. (optional)
    kind: media-import,
    // Set<JobStatus> | Only jobs in one of these states (comma-separated). (optional)
    status: ["queued","running"],
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListJobsRequest;

  try {
    const data = await api.listJobs(body);
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
| **kind** | `JobKind` | Only jobs of this kind. | [Optional] [Defaults to `undefined`] [Enum: dict-refresh, media-import, media-parse, jpdb-deck-import, jpdb-history-import, fsrs-optimize, tts, deck-build] |
| **status** | `Set<JobStatus>` | Only jobs in one of these states (comma-separated). | [Optional] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**JobPage**](JobPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of jobs. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## synthesizeSpeech

> Job synthesizeSpeech(ttsRequest)

Synthesize Japanese speech (VOICEVOX) into an audio blob.

Enqueues a job of kind &#x60;tts&#x60; (dedupe key &#x60;tts:&lt;sha256 of voice + NUL + text&gt;&#x60;); an equivalent queued or running job is returned with &#x60;200&#x60;. On success &#x60;job.result.blob_hash&#x60; names an audio blob (kind &#x60;audio&#x60;, origin &#x60;tts&#x60;). The caller then puts &#x60;[sound:&lt;hash&gt;]&#x60; into a note field with &#x60;PATCH /notes/{id}&#x60;; this call changes no note. 

### Example

```ts
import {
  Configuration,
  JobsApi,
} from '@kotobaya/api-client';
import type { SynthesizeSpeechRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new JobsApi(config);

  const body = {
    // TtsRequest
    ttsRequest: {"text":"パンを食べる"},
  } satisfies SynthesizeSpeechRequest;

  try {
    const data = await api.synthesizeSpeech(body);
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
| **ttsRequest** | [TtsRequest](TtsRequest.md) |  | |

### Return type

[**Job**](Job.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | An equivalent job is already queued or running. |  -  |
| **202** | Job queued. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

