# SyncApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**syncPull**](SyncApi.md#syncpull) | **GET** /sync/pull | Pull logged operations after a sequence number. |
| [**syncPush**](SyncApi.md#syncpush) | **POST** /sync/push | Push a batch of locally minted operations. |



## syncPull

> PullResponse syncPull(since, limit)

Pull logged operations after a sequence number.

**Body.** The &#x60;200&#x60; body is exactly &#x60;contracts/sync/batch.schema.json#/$defs/PullResponse&#x60; (no wrapper, no extra fields). &#x60;contracts/sync/SYNC.md&#x60; section 6 owns the semantics; this operation defines only the query parameters, auth and status codes.  **Lenient parsing.** The server produces &#x60;PullResponse&#x60;. A client SHOULD validate the body against &#x60;#/$defs/PullResponseLenient&#x60; (open &#x60;op_type&#x60; / &#x60;op_version&#x60;, unknown top-level properties ignored) and each op against &#x60;op.schema.json&#x60; only when it supports that type and version; at the first op it does not support it applies the ops before it and stops in state *upgrade required* (SYNC.md 6.1). Validating the whole page against &#x60;PullResponse&#x60; would make an old client reject a page it could partly apply.  **Semantics** (SYNC.md 6.1, restated, not extended): - &#x60;ops&#x60; are the &#x60;change&#x60; rows with &#x60;seq &gt; since&#x60;, ascending by &#x60;seq&#x60;, at most &#x60;limit&#x60;, from   **every** origin, including ops the caller pushed itself and ops that had no effect.   There is no option to skip one\&#39;s own ops. The server SHOULD also end a page before its   body would exceed &#x60;limits.max_push_bytes&#x60; (always at least one op), so pages stay   bounded although each op may be up to &#x60;limits.max_op_bytes&#x60;; &#x60;has_more&#x60; is then &#x60;true&#x60;. - &#x60;next_since&#x60; &#x3D; the &#x60;seq&#x60; of the last op returned, or &#x60;since&#x60; when &#x60;ops&#x60; is empty.   &#x60;has_more&#x60; &#x3D; rows exist after &#x60;next_since&#x60;; the client pulls again at once. - Every op carries its &#x60;seq&#x60; and &#x60;caused_by_op_id&#x60;. &#x60;seq&#x60; may have gaps. - The log is never pruned in v1: &#x60;since&#x3D;0&#x60; returns the whole history (new device, rebuild). - Every response carries &#x60;high_water_seq&#x60;, &#x60;server_time&#x60; (&#x60;instant_ms&#x60;), &#x60;log_epoch&#x60; and   &#x60;durable_seq&#x60;. Before applying a page the client compares &#x60;log_epoch&#x60; and   &#x60;high_water_seq&#x60; with its stored state and starts a log reset on a mismatch (SYNC.md 6.2). - &#x60;since&#x60; greater than &#x60;high_water_seq&#x60; (the server was restored from an older backup) is   **not** an error: the answer is &#x60;200&#x60; with &#x60;ops: []&#x60;, &#x60;next_since &#x3D; since&#x60;,   &#x60;has_more: false&#x60;, and the new &#x60;log_epoch&#x60; / smaller &#x60;high_water_seq&#x60; tell the client to   reset (SYNC.md 6.2). - Payloads are returned as logged; large content is never inline (ops such as   &#x60;deck.content_published&#x60; carry blob hashes to fetch with &#x60;GET /blobs/{hash}&#x60;).  Side effects when the token is bound to a device: sets &#x60;device.last_pull_seq &#x3D; since&#x60; (what the device has acknowledged) and &#x60;last_seen_at&#x60;. Tokens without a device may pull (read-only replicas, scripts). 

### Example

```ts
import {
  Configuration,
  SyncApi,
} from '@kotobaya/api-client';
import type { SyncPullRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new SyncApi(config);

  const body = {
    // number | Return log entries with `seq` greater than this. `0` = from the beginning. (optional)
    since: 13,
    // number | Maximum number of ops to return (`PullResponse.ops` has `maxItems` 1000). The server may return fewer; only `has_more` says whether more exist. (optional)
    limit: 500,
  } satisfies SyncPullRequest;

  try {
    const data = await api.syncPull(body);
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
| **since** | `number` | Return log entries with &#x60;seq&#x60; greater than this. &#x60;0&#x60; &#x3D; from the beginning. | [Optional] [Defaults to `0`] |
| **limit** | `number` | Maximum number of ops to return (&#x60;PullResponse.ops&#x60; has &#x60;maxItems&#x60; 1000). The server may return fewer; only &#x60;has_more&#x60; says whether more exist. | [Optional] [Defaults to `500`] |

### Return type

[**PullResponse**](PullResponse.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | A page of the log (&#x60;#/$defs/PullResponse&#x60;). |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## syncPush

> PushResponse syncPush(pushRequest)

Push a batch of locally minted operations.

**Bodies.** The request body is exactly &#x60;contracts/sync/batch.schema.json#/$defs/PushRequest&#x60; and the &#x60;200&#x60; body is exactly &#x60;#/$defs/PushResponse&#x60;; this operation adds no wrapper and no fields of its own. &#x60;contracts/sync/SYNC.md&#x60; sections 5.1-5.4 own the semantics (per-op procedure, result statuses &#x60;accepted&#x60; / &#x60;duplicate&#x60; / &#x60;rejected&#x60;, rejection codes, &#x60;high_water_seq&#x60;, &#x60;server_time&#x60; as &#x60;instant_ms&#x60;, &#x60;log_epoch&#x60;, &#x60;durable_seq&#x60;); this operation defines only the HTTP framing, auth and status codes. Where this text and SYNC.md disagree, SYNC.md wins.  **What the server validates as a whole.** The server validates the body against &#x60;#/$defs/PushRequestLenient&#x60; **only** (a JSON object with &#x60;ops&#x60;: 1-500 objects, each with a UUID &#x60;op_id&#x60;). If that fails the whole request is &#x60;400 invalid_request&#x60; and nothing is applied (&#x60;errors[]&#x60; points at the offending part). The schema &#x60;PushRequest&#x60; is what a correct client sends; the server never rejects a whole request because an op fails it.  **Duplicate keys (C-2, SYNC.md 5.2 \&quot;Parsing the body\&quot;).** The server parses the body with a syntax-only, duplicate-tolerant JSON parse. A key repeated in the top-level body object, an element whose &#x60;op_id&#x60; member is repeated, or two elements with the same &#x60;op_id&#x60; and different contents fail &#x60;PushRequestLenient&#x60; (&#x60;400 invalid_request&#x60;). A key repeated anywhere inside one element of &#x60;ops&#x60; is NOT a 400: that op is &#x60;rejected&#x60; with &#x60;schema_invalid&#x60;, decided before &#x60;op_too_large&#x60; (its canonical size is undefined). Clients MUST send each op exactly as &#x60;CanonicalJson.encode&#x60; / &#x60;OpCodec.encode&#x60; produce it, which never contains a duplicate key.  **Everything else is per op** (SYNC.md 5.2, 5.3), in array order, and reported as a &#x60;rejected&#x60; result while the remaining ops are still processed: an op over &#x60;limits.max_op_bytes&#x60; (&#x60;op_too_large&#x60;, checked first; SYNC.md 5.4), unknown op type or version (&#x60;unsupported_op&#x60;), an op invalid against &#x60;op.schema.json&#x60; (&#x60;schema_invalid&#x60;), an op whose &#x60;device_id&#x60; is not the caller\&#39;s device (&#x60;device_mismatch&#x60;), server-only ops (&#x60;server_only&#x60;), missing references (&#x60;unknown_reference&#x60;), a wrong device clock (&#x60;clock_ahead&#x60;, &#x60;clock_behind&#x60;; SYNC.md 3.4 and 10.4), &#x60;invalid_value&#x60;, &#x60;invalid_target&#x60;, &#x60;immutable&#x60;. This API adds one code to SYNC.md\&#39;s open set: &#x60;insufficient_scope&#x60; - an op of type &#x60;review.*&#x60; pushed by a token without the &#x60;review&#x60; scope; checked right after &#x60;device_mismatch&#x60; (SYNC.md 5.3 order). Clients treat unknown codes as permanent rejections (SYNC.md 5.3).  **Request-level errors** (nothing applied, rows stay &#x60;pending&#x60;, SYNC.md 5.4): - &#x60;401 unauthenticated&#x60;; &#x60;403 insufficient_scope&#x60; (no &#x60;write&#x60;); &#x60;403 device_required&#x60;   (the token is not bound to a device: only &#x60;device&#x60; tokens and &#x60;personal&#x60; tokens created   with &#x60;with_device&#x60; may push; session cookies have no device). - &#x60;400 invalid_request&#x60;: not JSON, or &#x60;PushRequestLenient&#x60; fails (empty &#x60;ops&#x60;, more than   500 ops, an op without a valid &#x60;op_id&#x60;, ...). - &#x60;413 payload_too_large&#x60;: body over &#x60;limits.max_push_bytes&#x60; (&#x60;GET /meta&#x60;, default 8 MiB).   This is about the batch, never about one op: a single op over &#x60;limits.max_op_bytes&#x60;   (default 1 MiB, at most &#x60;max_push_bytes - 4096&#x60;) is a per-op &#x60;op_too_large&#x60; result in   a &#x60;200&#x60;. On a &#x60;413&#x60; the client does not back off (SYNC.md 5.4): it halves the batch   (the first &#x60;ceil(n/2)&#x60; ops, still in ascending &#x60;hlc&#x60;) and pushes again at once, down to   a single op. If a single op still gets &#x60;413&#x60; (the server\&#39;s limits are inconsistent or   the client\&#39;s view of them is stale), the client marks that row rejected with   &#x60;op_too_large&#x60; itself and continues with the next ops; it is then an ordinary rejected   op (SYNC.md 10). - &#x60;415&#x60;, &#x60;429&#x60;, &#x60;5xx&#x60; as everywhere. There is no whole-request &#x60;409&#x60; and no whole-request &#x60;422&#x60; on this operation.  **Status.** &#x60;200&#x60; whenever the body passed &#x60;PushRequestLenient&#x60;, even if every op was rejected. &#x60;results&#x60; has exactly one entry per pushed op, in request order. Ops the server emits as consequences (&#x60;note.merged&#x60;, &#x60;card.merged&#x60;, ...) are not in &#x60;results&#x60;; they arrive through &#x60;GET /sync/pull&#x60;.  **Retry.** On no response, &#x60;5xx&#x60; or &#x60;429&#x60; the client re-sends the same ops (SYNC.md 5.4); ops already committed come back as &#x60;duplicate&#x60; with their original &#x60;seq&#x60;. Before using a response the client compares &#x60;log_epoch&#x60; and &#x60;high_water_seq&#x60; with its stored state (SYNC.md 6.2, log reset) and checks &#x60;server_time&#x60; against its own clock (SYNC.md 3.4).  Side effects: updates &#x60;device.last_push_at&#x60; and &#x60;last_seen_at&#x60;; emits the event &#x60;sync.changed&#x60; when at least one op was accepted. 

### Example

```ts
import {
  Configuration,
  SyncApi,
} from '@kotobaya/api-client';
import type { SyncPushRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new SyncApi(config);

  const body = {
    // PushRequest
    pushRequest: {"ops":[{"device_id":"01931f3e-0000-7000-8000-0000000000b2","entity_id":"0193a1b2-0001-7000-8000-000000000002","entity_type":"note","hlc":"0193a1b2c014-0000-01931f3e-0000-7000-8000-0000000000b2","op_id":"0193a1b2-00ff-7005-8000-000000000004","op_type":"note.created","op_version":1,"payload":{"entry_seq":1358280,"fields":{"meaning":"","sentence":"パンを食べる"},"id":"0193a1b2-0001-7000-8000-000000000002","note_type_id":"00000000-0000-7000-8000-000000000101","origin":"takoboto"}},{"device_id":"01931f3e-0000-7000-8000-0000000000b2","entity_id":"0193a1b2-0002-7000-8000-000000000002","entity_type":"card","hlc":"0193a1b2c015-0000-01931f3e-0000-7000-8000-0000000000b2","op_id":"0193a1b2-00ff-7005-8000-000000000006","op_type":"card.created","op_version":1,"payload":{"id":"0193a1b2-0002-7000-8000-000000000002","kind":"meaning","note_id":"0193a1b2-0001-7000-8000-000000000002"}},{"device_id":"01931f3e-0000-7000-8000-0000000000b2","entity_id":"0193a1b2-0002-7000-8000-000000000003","entity_type":"card","hlc":"0193a1b2c016-0000-01931f3e-0000-7000-8000-0000000000b2","op_id":"0193a1b2-00ff-7005-8000-000000000008","op_type":"card.created","op_version":1,"payload":{"id":"0193a1b2-0002-7000-8000-000000000003","kind":"reading","note_id":"0193a1b2-0001-7000-8000-000000000002"}},{"device_id":"01931f3e-0000-7000-8000-0000000000b2","entity_id":"0193a1b2-0001-7000-8000-000000000002","entity_type":"note","hlc":"0193a1b2c01e-0000-01931f3e-0000-7000-8000-0000000000b2","op_id":"0193a1b2-00ff-7005-8000-00000000000b","op_type":"note.updated","op_version":1,"payload":{"id":"0193a1b2-0001-7000-8000-000000000002","set":{"tags":["anime"]}}},{"device_id":"01931f3e-0000-7000-8000-0000000000b2","entity_id":"0193a1b2-0002-7000-8000-000000000002","entity_type":"card","hlc":"0193a1b2c01f-0000-01931f3e-0000-7000-8000-0000000000b2","op_id":"0193a1b2-00ff-7005-8000-00000000000c","op_type":"card.suspended","op_version":1,"payload":{"id":"0193a1b2-0002-7000-8000-000000000002"}},{"device_id":"01931f3e-0000-7000-8000-0000000000b2","entity_id":"0193a1b2-0003-7000-8000-000000000001","entity_type":"review_log","hlc":"0193a1b2c020-0000-01931f3e-0000-7000-8000-0000000000b2","op_id":"0193a1b2-00ff-7005-8000-00000000000d","op_type":"review.logged","op_version":1,"payload":{"button":"good","card_id":"0193a1b2-0002-7000-8000-000000000002","duration_ms":4200,"id":"0193a1b2-0003-7000-8000-000000000001","rating":3,"review_type":"learning","reviewed_at":1733584666656,"utc_offset_min":540}}]},
  } satisfies SyncPushRequest;

  try {
    const data = await api.syncPush(body);
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
| **pushRequest** | [PushRequest](PushRequest.md) |  | |

### Return type

[**PushResponse**](PushResponse.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One result per pushed op, in request order (&#x60;#/$defs/PushResponse&#x60;). |  -  |
| **400** | The body is not valid JSON, or (for &#x60;POST /sync/push&#x60;) does not match &#x60;PushRequestLenient&#x60;. |  -  |
| **403** | The credential is valid but may not do this. |  -  |
| **413** | The body exceeds a limit from &#x60;GET /meta&#x60;. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

