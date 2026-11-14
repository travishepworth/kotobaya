# MetaApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getHealth**](MetaApi.md#gethealth) | **GET** /health | Liveness and readiness probe. |
| [**getMeta**](MetaApi.md#getmeta) | **GET** /meta | Server versions, limits and sync head. |
| [**reportClientErrors**](MetaApi.md#reportclienterrors) | **POST** /client-errors | Report client-side errors (web, Android) to the server log. |



## getHealth

> Health getHealth()

Liveness and readiness probe.

Public. &#x60;200&#x60; when the API can reach Postgres, &#x60;503&#x60; (problem &#x60;unavailable&#x60;) otherwise. Does not check the object store or the worker. 

### Example

```ts
import {
  Configuration,
  MetaApi,
} from '@kotobaya/api-client';
import type { GetHealthRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const api = new MetaApi();

  try {
    const data = await api.getHealth();
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

[**Health**](Health.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Healthy. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getMeta

> Meta getMeta()

Server versions, limits and sync head.

### Example

```ts
import {
  Configuration,
  MetaApi,
} from '@kotobaya/api-client';
import type { GetMetaRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MetaApi(config);

  try {
    const data = await api.getMeta();
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

[**Meta**](Meta.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Server information. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## reportClientErrors

> ClientErrorReportAccepted reportClientErrors(clientErrorReport)

Report client-side errors (web, Android) to the server log.

Added by C-23 (OBS-1, ADR-024). A client sends a batch of errors it observed (uncaught exceptions, unhandled promise rejections, crashes, failed API calls); the server writes them to its structured log and **stores nothing**: no table, no blob, no op, no job. Duplicate log lines from a retried request are possible and harmless.  **Who may call.** Any valid credential of any kind (&#x60;session&#x60;, &#x60;device&#x60;, &#x60;personal&#x60;) with any scopes, like &#x60;getPrincipal&#x60;. No credential, or an expired or revoked one: &#x60;401 unauthenticated&#x60;. Cookie-authenticated calls need &#x60;X-Kotobaya-Csrf: 1&#x60; like every other cookie write (&#x60;403 csrf_required&#x60;), so web clients send reports with &#x60;fetch(..., {keepalive: true})&#x60;, not &#x60;navigator.sendBeacon&#x60; (which cannot set headers).  **Limits and check order** (fixed constants, not published in &#x60;GET /meta&#x60;): 1. &#x60;401&#x60; / &#x60;403&#x60; as for every operation. 2. Rate limit, requests: per credential (&#x60;api_token.id&#x60;) at most **12 requests** in any    rolling 60-second window. The 13th is &#x60;429 rate_limited&#x60; before the body is read. 3. Body over **1 MiB** (&#x60;1048576&#x60; bytes of raw request body): &#x60;413 payload_too_large&#x60;. 4. Body not JSON: &#x60;400 invalid_request&#x60;. Wrong content type: &#x60;415&#x60;. 5. More than **20** &#x60;items&#x60;: &#x60;413 payload_too_large&#x60;. The client splits the batch and    resends the halves; one item within the field limits always fits in 1 MiB. 6. Any field violation (empty &#x60;items&#x60;, &#x60;message&#x60; over 2048 characters, &#x60;stack&#x60; over    16384, unknown property, bad enum, ...): &#x60;422 validation_failed&#x60; with one &#x60;errors[]&#x60;    element per problem (&#x60;required&#x60;, &#x60;unknown_field&#x60;, &#x60;invalid_type&#x60;, &#x60;invalid_value&#x60;,    &#x60;out_of_range&#x60;, &#x60;too_long&#x60;). The whole batch is rejected; the server logs one WARN    line naming the first problem, not the items. Clients MUST truncate &#x60;message&#x60; and    &#x60;stack&#x60; to the limits before sending (characters are Unicode code points, as JSON    Schema &#x60;maxLength&#x60; counts) and SHOULD end a truncated value with &#x60;…&#x60;. 7. Rate limit, items: per credential at most **120 items** in any rolling 60-second    window; a batch that would exceed it is &#x60;429 rate_limited&#x60; as a whole (no partial    acceptance) and none of its items are logged. 8. &#x60;202&#x60;: every item is logged. &#x60;429&#x60; carries &#x60;Retry-After&#x60; (whole seconds, at least 1, until the window admits the request). Every request that passes step 2 counts toward the request limit, whatever its outcome; only &#x60;202&#x60; requests count toward the item limit. The server logs at most one WARN line per credential per 60-second window saying reports were refused (token name, refused request and item counts). Limiter state is in memory per API replica (like the login limit) and resets on restart.  **What the server logs** (normative for OBS-1s). One line per item, logger &#x60;kotobaya.client_error&#x60;, level &#x60;ERROR&#x60; for &#x60;level: error&#x60; and &#x60;WARN&#x60; for &#x60;level: warn&#x60;, with the key-value fields &#x60;client&#x60;, &#x60;app_version&#x60;, &#x60;kind&#x60;, &#x60;occurred_at&#x60; (normalised to UTC), &#x60;route&#x60;, &#x60;client_request_id&#x60; (the item\&#39;s &#x60;request_id&#x60;; the request\&#39;s own id stays &#x60;request_id&#x60;), &#x60;http_method&#x60;, &#x60;http_path&#x60;, &#x60;http_status&#x60;, &#x60;count&#x60;, the calling token\&#39;s name and device id (from the credential, never from the body), &#x60;message&#x60; and &#x60;stack&#x60;. Absent optional properties are omitted from the line. Every string from the body is escaped before logging so one item is exactly one line: CR, LF, TAB and every other C0/C1 control character (including U+0000) become &#x60;\\r&#x60;, &#x60;\\n&#x60;, &#x60;\\t&#x60; or &#x60;\\u00XX&#x60;, and U+2028/U+2029 become &#x60;\\u2028&#x60;/&#x60;\\u2029&#x60;. &#x60;route&#x60; and &#x60;http_path&#x60; are cut at the first &#x60;?&#x60; or &#x60;#&#x60; before logging (query strings and fragments can hold search text). When &#x60;dropped &gt; 0&#x60; the server also logs one WARN line with &#x60;dropped&#x60;, the client, app version and token name.  **Clock skew.** &#x60;occurred_at&#x60; is the client\&#39;s clock and is never rejected for being in the past or the future (only for not being an RFC 3339 instant, or for being after &#x60;9999-12-31T23:59:59.999Z&#x60;); the log line\&#39;s own timestamp is the server\&#39;s receive time.  **Privacy.** No PII beyond the token name the server adds. Clients MUST NOT put request or response bodies, note or card content, search strings, tokens, cookies or &#x60;Authorization&#x60; values into any property; &#x60;message&#x60; is the error\&#39;s own message.  **Client obligations** (normative for OBS-1c): - Never report a failure of &#x60;reportClientErrors&#x60; itself (no feedback loop). - Fold identical reports (same &#x60;client&#x60;, &#x60;kind&#x60;, &#x60;level&#x60;, &#x60;message&#x60;, &#x60;route&#x60;,   &#x60;http_method&#x60;, &#x60;http_path&#x60;, &#x60;http_status&#x60;) into one item: &#x60;count&#x60; is the number of   occurrences, &#x60;occurred_at&#x60; the first one. - Keep a bounded queue (web: in memory; Android: persisted, so it survives offline time   and restarts) of at most 100 pending items; on overflow drop the oldest and add 1 to   &#x60;dropped&#x60;. Send &#x60;dropped&#x60; with the next batch and reset it only when that batch is   answered &#x60;202&#x60;. - &#x60;202&#x60;: remove the sent items. &#x60;413&#x60;: split the batch and resend. &#x60;429&#x60;: keep the   items and wait &#x60;Retry-After&#x60;. Network failure, &#x60;500&#x60;, &#x60;503&#x60;: keep the items and retry   with exponential backoff (at least 30 s, at most 15 min). &#x60;401&#x60;: keep the items and do   not send until a credential is available again. Any other status: drop the batch and   add its item count to &#x60;dropped&#x60; (it can never succeed). 

### Example

```ts
import {
  Configuration,
  MetaApi,
} from '@kotobaya/api-client';
import type { ReportClientErrorsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MetaApi(config);

  const body = {
    // ClientErrorReport
    clientErrorReport: {"dropped":3,"items":[{"app_version":"0.2.0 (21)","client":"android","kind":"crash","level":"error","message":"java.lang.IllegalStateException: queue empty","occurred_at":"2026-11-14T07:59:40.000Z","route":"review","stack":"java.lang.IllegalStateException: queue empty\n\tat app.kotobaya.review.ReviewViewModel.next(ReviewViewModel.kt:88)"}]},
  } satisfies ReportClientErrorsRequest;

  try {
    const data = await api.reportClientErrors(body);
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
| **clientErrorReport** | [ClientErrorReport](ClientErrorReport.md) |  | |

### Return type

[**ClientErrorReportAccepted**](ClientErrorReportAccepted.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **202** | Every item was accepted and logged. |  -  |
| **401** | Missing, unknown, expired or revoked credential; or wrong login or pairing data. |  -  |
| **413** | The body exceeds a limit from &#x60;GET /meta&#x60;. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **429** | Too many attempts. |  * Retry-After - Seconds until the next attempt is allowed. <br>  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

