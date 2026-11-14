# kotobaya_client.MetaApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**get_health**](MetaApi.md#get_health) | **GET** /health | Liveness and readiness probe.
[**get_meta**](MetaApi.md#get_meta) | **GET** /meta | Server versions, limits and sync head.
[**report_client_errors**](MetaApi.md#report_client_errors) | **POST** /client-errors | Report client-side errors (web, Android) to the server log.


# **get_health**
> Health get_health()

Liveness and readiness probe.

Public. `200` when the API can reach Postgres, `503` (problem `unavailable`) otherwise.
Does not check the object store or the worker.


### Example


```python
import kotobaya_client
from kotobaya_client.models.health import Health
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)


# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.MetaApi(api_client)

    try:
        # Liveness and readiness probe.
        api_response = api_instance.get_health()
        print("The response of MetaApi->get_health:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MetaApi->get_health: %s\n" % e)
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

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Healthy. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_meta**
> Meta get_meta()

Server versions, limits and sync head.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.meta import Meta
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
    api_instance = kotobaya_client.MetaApi(api_client)

    try:
        # Server versions, limits and sync head.
        api_response = api_instance.get_meta()
        print("The response of MetaApi->get_meta:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MetaApi->get_meta: %s\n" % e)
```



### Parameters

This endpoint does not need any parameter.

### Return type

[**Meta**](Meta.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Server information. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **report_client_errors**
> ClientErrorReportAccepted report_client_errors(client_error_report)

Report client-side errors (web, Android) to the server log.

Added by C-23 (OBS-1, ADR-024). A client sends a batch of errors it observed (uncaught
exceptions, unhandled promise rejections, crashes, failed API calls); the server writes
them to its structured log and **stores nothing**: no table, no blob, no op, no job.
Duplicate log lines from a retried request are possible and harmless.

**Who may call.** Any valid credential of any kind (`session`, `device`, `personal`) with
any scopes, like `getPrincipal`. No credential, or an expired or revoked one:
`401 unauthenticated`. Cookie-authenticated calls need `X-Kotobaya-Csrf: 1` like every
other cookie write (`403 csrf_required`), so web clients send reports with
`fetch(..., {keepalive: true})`, not `navigator.sendBeacon` (which cannot set headers).

**Limits and check order** (fixed constants, not published in `GET /meta`):
1. `401` / `403` as for every operation.
2. Rate limit, requests: per credential (`api_token.id`) at most **12 requests** in any
   rolling 60-second window. The 13th is `429 rate_limited` before the body is read.
3. Body over **1 MiB** (`1048576` bytes of raw request body): `413 payload_too_large`.
4. Body not JSON: `400 invalid_request`. Wrong content type: `415`.
5. More than **20** `items`: `413 payload_too_large`. The client splits the batch and
   resends the halves; one item within the field limits always fits in 1 MiB.
6. Any field violation (empty `items`, `message` over 2048 characters, `stack` over
   16384, unknown property, bad enum, ...): `422 validation_failed` with one `errors[]`
   element per problem (`required`, `unknown_field`, `invalid_type`, `invalid_value`,
   `out_of_range`, `too_long`). The whole batch is rejected; the server logs one WARN
   line naming the first problem, not the items. Clients MUST truncate `message` and
   `stack` to the limits before sending (characters are Unicode code points, as JSON
   Schema `maxLength` counts) and SHOULD end a truncated value with `…`.
7. Rate limit, items: per credential at most **120 items** in any rolling 60-second
   window; a batch that would exceed it is `429 rate_limited` as a whole (no partial
   acceptance) and none of its items are logged.
8. `202`: every item is logged.
`429` carries `Retry-After` (whole seconds, at least 1, until the window admits the
request). Every request that passes step 2 counts toward the request limit, whatever its
outcome; only `202` requests count toward the item limit. The server logs at most one
WARN line per credential per 60-second window saying reports were refused (token name,
refused request and item counts). Limiter state is in memory per API replica (like the
login limit) and resets on restart.

**What the server logs** (normative for OBS-1s). One line per item, logger
`kotobaya.client_error`, level `ERROR` for `level: error` and `WARN` for `level: warn`,
with the key-value fields `client`, `app_version`, `kind`, `occurred_at` (normalised to
UTC), `route`, `client_request_id` (the item's `request_id`; the request's own id stays
`request_id`), `http_method`, `http_path`, `http_status`, `count`, the calling token's
name and device id (from the credential, never from the body), `message` and `stack`.
Absent optional properties are omitted from the line. Every string from the body is
escaped before logging so one item is exactly one line: CR, LF, TAB and every other
C0/C1 control character (including U+0000) become `\r`, `\n`, `\t` or `\u00XX`, and
U+2028/U+2029 become `\u2028`/`\u2029`. `route` and `http_path` are cut at the first `?`
or `#` before logging (query strings and fragments can hold search text). When
`dropped > 0` the server also logs one WARN line with `dropped`, the client, app version
and token name.

**Clock skew.** `occurred_at` is the client's clock and is never rejected for being in
the past or the future (only for not being an RFC 3339 instant, or for being after
`9999-12-31T23:59:59.999Z`); the log line's own timestamp is the server's receive time.

**Privacy.** No PII beyond the token name the server adds. Clients MUST NOT put request
or response bodies, note or card content, search strings, tokens, cookies or
`Authorization` values into any property; `message` is the error's own message.

**Client obligations** (normative for OBS-1c):
- Never report a failure of `reportClientErrors` itself (no feedback loop).
- Fold identical reports (same `client`, `kind`, `level`, `message`, `route`,
  `http_method`, `http_path`, `http_status`) into one item: `count` is the number of
  occurrences, `occurred_at` the first one.
- Keep a bounded queue (web: in memory; Android: persisted, so it survives offline time
  and restarts) of at most 100 pending items; on overflow drop the oldest and add 1 to
  `dropped`. Send `dropped` with the next batch and reset it only when that batch is
  answered `202`.
- `202`: remove the sent items. `413`: split the batch and resend. `429`: keep the
  items and wait `Retry-After`. Network failure, `500`, `503`: keep the items and retry
  with exponential backoff (at least 30 s, at most 15 min). `401`: keep the items and do
  not send until a credential is available again. Any other status: drop the batch and
  add its item count to `dropped` (it can never succeed).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.client_error_report import ClientErrorReport
from kotobaya_client.models.client_error_report_accepted import ClientErrorReportAccepted
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
    api_instance = kotobaya_client.MetaApi(api_client)
    client_error_report = {"dropped":3,"items":[{"app_version":"0.2.0 (21)","client":"android","kind":"crash","level":"error","message":"java.lang.IllegalStateException: queue empty","occurred_at":"2026-11-14T07:59:40.000Z","route":"review","stack":"java.lang.IllegalStateException: queue empty\n\tat app.kotobaya.review.ReviewViewModel.next(ReviewViewModel.kt:88)"}]} # ClientErrorReport | 

    try:
        # Report client-side errors (web, Android) to the server log.
        api_response = api_instance.report_client_errors(client_error_report)
        print("The response of MetaApi->report_client_errors:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling MetaApi->report_client_errors: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **client_error_report** | [**ClientErrorReport**](ClientErrorReport.md)|  | 

### Return type

[**ClientErrorReportAccepted**](ClientErrorReportAccepted.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**202** | Every item was accepted and logged. |  -  |
**401** | Missing, unknown, expired or revoked credential; or wrong login or pairing data. |  -  |
**413** | The body exceeds a limit from &#x60;GET /meta&#x60;. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**429** | Too many attempts. |  * Retry-After - Seconds until the next attempt is allowed. <br>  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

