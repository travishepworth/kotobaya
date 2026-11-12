# kotobaya_client.SyncApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**sync_pull**](SyncApi.md#sync_pull) | **GET** /sync/pull | Pull logged operations after a sequence number.
[**sync_push**](SyncApi.md#sync_push) | **POST** /sync/push | Push a batch of locally minted operations.


# **sync_pull**
> PullResponse sync_pull(since=since, limit=limit)

Pull logged operations after a sequence number.

**Body.** The `200` body is exactly `contracts/sync/batch.schema.json#/$defs/PullResponse`
(no wrapper, no extra fields). `contracts/sync/SYNC.md` section 6 owns the semantics; this
operation defines only the query parameters, auth and status codes.

**Lenient parsing.** The server produces `PullResponse`. A client SHOULD validate the body
against `#/$defs/PullResponseLenient` (open `op_type` / `op_version`, unknown top-level
properties ignored) and each op against `op.schema.json` only when it supports that type and
version; at the first op it does not support it applies the ops before it and stops in
state *upgrade required* (SYNC.md 6.1). Validating the whole page against `PullResponse`
would make an old client reject a page it could partly apply.

**Semantics** (SYNC.md 6.1, restated, not extended):
- `ops` are the `change` rows with `seq > since`, ascending by `seq`, at most `limit`, from
  **every** origin, including ops the caller pushed itself and ops that had no effect.
  There is no option to skip one's own ops. The server SHOULD also end a page before its
  body would exceed `limits.max_push_bytes` (always at least one op), so pages stay
  bounded although each op may be up to `limits.max_op_bytes`; `has_more` is then `true`.
- `next_since` = the `seq` of the last op returned, or `since` when `ops` is empty.
  `has_more` = rows exist after `next_since`; the client pulls again at once.
- Every op carries its `seq` and `caused_by_op_id`. `seq` may have gaps.
- The log is never pruned in v1: `since=0` returns the whole history (new device, rebuild).
- Every response carries `high_water_seq`, `server_time` (`instant_ms`), `log_epoch` and
  `durable_seq`. Before applying a page the client compares `log_epoch` and
  `high_water_seq` with its stored state and starts a log reset on a mismatch (SYNC.md 6.2).
- `since` greater than `high_water_seq` (the server was restored from an older backup) is
  **not** an error: the answer is `200` with `ops: []`, `next_since = since`,
  `has_more: false`, and the new `log_epoch` / smaller `high_water_seq` tell the client to
  reset (SYNC.md 6.2).
- Payloads are returned as logged; large content is never inline (ops such as
  `deck.content_published` carry blob hashes to fetch with `GET /blobs/{hash}`).

Side effects when the token is bound to a device: sets `device.last_pull_seq = since`
(what the device has acknowledged) and `last_seen_at`. Tokens without a device may pull
(read-only replicas, scripts).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.pull_response import PullResponse
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.SyncApi(api_client)
    since = 0 # int | Return log entries with `seq` greater than this. `0` = from the beginning. (optional) (default to 0)
    limit = 500 # int | Maximum number of ops to return (`PullResponse.ops` has `maxItems` 1000). The server may return fewer; only `has_more` says whether more exist. (optional) (default to 500)

    try:
        # Pull logged operations after a sequence number.
        api_response = api_instance.sync_pull(since=since, limit=limit)
        print("The response of SyncApi->sync_pull:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling SyncApi->sync_pull: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **since** | **int**| Return log entries with &#x60;seq&#x60; greater than this. &#x60;0&#x60; &#x3D; from the beginning. | [optional] [default to 0]
 **limit** | **int**| Maximum number of ops to return (&#x60;PullResponse.ops&#x60; has &#x60;maxItems&#x60; 1000). The server may return fewer; only &#x60;has_more&#x60; says whether more exist. | [optional] [default to 500]

### Return type

[**PullResponse**](PullResponse.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | A page of the log (&#x60;#/$defs/PullResponse&#x60;). |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **sync_push**
> PushResponse sync_push(push_request)

Push a batch of locally minted operations.

**Bodies.** The request body is exactly `contracts/sync/batch.schema.json#/$defs/PushRequest`
and the `200` body is exactly `#/$defs/PushResponse`; this operation adds no wrapper and
no fields of its own. `contracts/sync/SYNC.md` sections 5.1-5.4 own the semantics (per-op
procedure, result statuses `accepted` / `duplicate` / `rejected`, rejection codes,
`high_water_seq`, `server_time` as `instant_ms`, `log_epoch`, `durable_seq`); this
operation defines only the HTTP framing, auth and status codes. Where this text and
SYNC.md disagree, SYNC.md wins.

**What the server validates as a whole.** The server validates the body against
`#/$defs/PushRequestLenient` **only** (a JSON object with `ops`: 1-500 objects, each with
a UUID `op_id`). If that fails the whole request is `400 invalid_request` and nothing is
applied (`errors[]` points at the offending part). The schema `PushRequest` is what a
correct client sends; the server never rejects a whole request because an op fails it.

**Duplicate keys (C-2, SYNC.md 5.2 "Parsing the body").** The server parses the body with
a syntax-only, duplicate-tolerant JSON parse. A key repeated in the top-level body
object, an element whose `op_id` member is repeated, or two elements with the same
`op_id` and different contents fail `PushRequestLenient` (`400 invalid_request`). A key
repeated anywhere inside one element of `ops` is NOT a 400: that op is `rejected` with
`schema_invalid`, decided before `op_too_large` (its canonical size is undefined).
Clients MUST send each op exactly as `CanonicalJson.encode` / `OpCodec.encode` produce
it, which never contains a duplicate key.

**Everything else is per op** (SYNC.md 5.2, 5.3), in array order, and reported as a
`rejected` result while the remaining ops are still processed: an op over
`limits.max_op_bytes` (`op_too_large`, checked first; SYNC.md 5.4), unknown op type or
version (`unsupported_op`), an op invalid against `op.schema.json` (`schema_invalid`),
an op whose `device_id` is not the caller's device (`device_mismatch`), server-only ops
(`server_only`), missing references (`unknown_reference`), a wrong device clock
(`clock_ahead`, `clock_behind`; SYNC.md 3.4 and 10.4), `invalid_value`,
`invalid_target`, `immutable`. This API adds one code to SYNC.md's open set:
`insufficient_scope` - an op of type `review.*` pushed by a token without the `review`
scope; checked right after `device_mismatch` (SYNC.md 5.3 order). Clients treat unknown codes as permanent rejections (SYNC.md 5.3).

**Request-level errors** (nothing applied, rows stay `pending`, SYNC.md 5.4):
- `401 unauthenticated`; `403 insufficient_scope` (no `write`); `403 device_required`
  (the token is not bound to a device: only `device` tokens and `personal` tokens created
  with `with_device` may push; session cookies have no device).
- `400 invalid_request`: not JSON, or `PushRequestLenient` fails (empty `ops`, more than
  500 ops, an op without a valid `op_id`, ...).
- `413 payload_too_large`: body over `limits.max_push_bytes` (`GET /meta`, default 8 MiB).
  This is about the batch, never about one op: a single op over `limits.max_op_bytes`
  (default 1 MiB, at most `max_push_bytes - 4096`) is a per-op `op_too_large` result in
  a `200`. On a `413` the client does not back off (SYNC.md 5.4): it halves the batch
  (the first `ceil(n/2)` ops, still in ascending `hlc`) and pushes again at once, down to
  a single op. If a single op still gets `413` (the server's limits are inconsistent or
  the client's view of them is stale), the client marks that row rejected with
  `op_too_large` itself and continues with the next ops; it is then an ordinary rejected
  op (SYNC.md 10).
- `415`, `429`, `5xx` as everywhere.
There is no whole-request `409` and no whole-request `422` on this operation.

**Status.** `200` whenever the body passed `PushRequestLenient`, even if every op was
rejected. `results` has exactly one entry per pushed op, in request order. Ops the server
emits as consequences (`note.merged`, `card.merged`, ...) are not in `results`; they
arrive through `GET /sync/pull`.

**Retry.** On no response, `5xx` or `429` the client re-sends the same ops (SYNC.md 5.4);
ops already committed come back as `duplicate` with their original `seq`. Before using a
response the client compares `log_epoch` and `high_water_seq` with its stored state
(SYNC.md 6.2, log reset) and checks `server_time` against its own clock (SYNC.md 3.4).

Side effects: updates `device.last_push_at` and `last_seen_at`; emits the event
`sync.changed` when at least one op was accepted.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.push_request import PushRequest
from kotobaya_client.models.push_response import PushResponse
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.SyncApi(api_client)
    push_request = {"ops":[{"device_id":"01931f3e-0000-7000-8000-0000000000b2","entity_id":"0193a1b2-0001-7000-8000-000000000002","entity_type":"note","hlc":"0193a1b2c014-0000-01931f3e-0000-7000-8000-0000000000b2","op_id":"0193a1b2-00ff-7005-8000-000000000004","op_type":"note.created","op_version":1,"payload":{"entry_seq":1358280,"fields":{"meaning":"","sentence":"パンを食べる"},"id":"0193a1b2-0001-7000-8000-000000000002","note_type_id":"00000000-0000-7000-8000-000000000101","origin":"takoboto"}},{"device_id":"01931f3e-0000-7000-8000-0000000000b2","entity_id":"0193a1b2-0002-7000-8000-000000000002","entity_type":"card","hlc":"0193a1b2c015-0000-01931f3e-0000-7000-8000-0000000000b2","op_id":"0193a1b2-00ff-7005-8000-000000000006","op_type":"card.created","op_version":1,"payload":{"id":"0193a1b2-0002-7000-8000-000000000002","kind":"meaning","note_id":"0193a1b2-0001-7000-8000-000000000002"}},{"device_id":"01931f3e-0000-7000-8000-0000000000b2","entity_id":"0193a1b2-0002-7000-8000-000000000003","entity_type":"card","hlc":"0193a1b2c016-0000-01931f3e-0000-7000-8000-0000000000b2","op_id":"0193a1b2-00ff-7005-8000-000000000008","op_type":"card.created","op_version":1,"payload":{"id":"0193a1b2-0002-7000-8000-000000000003","kind":"reading","note_id":"0193a1b2-0001-7000-8000-000000000002"}},{"device_id":"01931f3e-0000-7000-8000-0000000000b2","entity_id":"0193a1b2-0001-7000-8000-000000000002","entity_type":"note","hlc":"0193a1b2c01e-0000-01931f3e-0000-7000-8000-0000000000b2","op_id":"0193a1b2-00ff-7005-8000-00000000000b","op_type":"note.updated","op_version":1,"payload":{"id":"0193a1b2-0001-7000-8000-000000000002","set":{"tags":["anime"]}}},{"device_id":"01931f3e-0000-7000-8000-0000000000b2","entity_id":"0193a1b2-0002-7000-8000-000000000002","entity_type":"card","hlc":"0193a1b2c01f-0000-01931f3e-0000-7000-8000-0000000000b2","op_id":"0193a1b2-00ff-7005-8000-00000000000c","op_type":"card.suspended","op_version":1,"payload":{"id":"0193a1b2-0002-7000-8000-000000000002"}},{"device_id":"01931f3e-0000-7000-8000-0000000000b2","entity_id":"0193a1b2-0003-7000-8000-000000000001","entity_type":"review_log","hlc":"0193a1b2c020-0000-01931f3e-0000-7000-8000-0000000000b2","op_id":"0193a1b2-00ff-7005-8000-00000000000d","op_type":"review.logged","op_version":1,"payload":{"button":"good","card_id":"0193a1b2-0002-7000-8000-000000000002","duration_ms":4200,"id":"0193a1b2-0003-7000-8000-000000000001","rating":3,"review_type":"learning","reviewed_at":1733584666656,"utc_offset_min":540}}]} # PushRequest | 

    try:
        # Push a batch of locally minted operations.
        api_response = api_instance.sync_push(push_request)
        print("The response of SyncApi->sync_push:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling SyncApi->sync_push: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **push_request** | [**PushRequest**](PushRequest.md)|  | 

### Return type

[**PushResponse**](PushResponse.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One result per pushed op, in request order (&#x60;#/$defs/PushResponse&#x60;). |  -  |
**400** | The body is not valid JSON, or (for &#x60;POST /sync/push&#x60;) does not match &#x60;PushRequestLenient&#x60;. |  -  |
**403** | The credential is valid but may not do this. |  -  |
**413** | The body exceeds a limit from &#x60;GET /meta&#x60;. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

