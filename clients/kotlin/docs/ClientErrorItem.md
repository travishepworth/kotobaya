
# ClientErrorItem

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **appVersion** | **kotlin.String** | The client&#39;s own version string (web build version; Android &#x60;versionName (versionCode)&#x60;). Printable ASCII. |  |
| **client** | [**inline**](#Client) | Which app observed the error. |  |
| **kind** | [**inline**](#Kind) | &#x60;exception&#x60;: a caught-at-top-level or uncaught exception that did not end the app (web &#x60;window.onerror&#x60;, Android handled error boundary). &#x60;api_failure&#x60;: an API call answered with a status the client did not expect (5xx, or a 4xx that indicates a bug), or that failed at the network level; set the &#x60;http_*&#x60; properties. &#x60;unhandled_rejection&#x60;: web &#x60;unhandledrejection&#x60;. &#x60;crash&#x60;: the app process died (Android uncaught-exception handler; reported on the next start).  |  |
| **level** | [**inline**](#Level) | &#x60;error&#x60; is logged at ERROR, &#x60;warn&#x60; at WARN. Crashes, uncaught exceptions and 5xx/network API failures are &#x60;error&#x60;; a recovered or expected-but-odd condition is &#x60;warn&#x60;. |  |
| **message** | **kotlin.String** | The error message (for example &#x60;TypeError: x is undefined&#x60;), at most 2048 code points; clients truncate. No PII, note content or credentials. |  |
| **occurredAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | When the error happened (first occurrence when &#x60;count &gt; 1&#x60;), by the client&#39;s clock. Never rejected for skew. |  |
| **count** | **kotlin.Int** | Optional, default 1. Number of identical occurrences folded into this item by the client. |  [optional] |
| **httpMethod** | [**inline**](#HttpMethod) | Optional; for &#x60;api_failure&#x60;. Method of the failed call. |  [optional] |
| **httpPath** | **kotlin.String** | Optional; for &#x60;api_failure&#x60;. Path of the failed call, without the query string (the server cuts at the first &#x60;?&#x60; or &#x60;#&#x60;). |  [optional] |
| **httpStatus** | **kotlin.Int** | Optional; for &#x60;api_failure&#x60;. Response status of the failed call; absent when no response arrived (network failure or timeout). |  [optional] |
| **requestId** | **kotlin.String** | Optional. The &#x60;X-Request-Id&#x60; response header (or &#x60;Problem.request_id&#x60;) of the failed API call, tying the report to the server&#39;s own log line. Logged as &#x60;client_request_id&#x60;. |  [optional] |
| **route** | **kotlin.String** | Optional. Web - the SPA route path (&#x60;/review&#x60;, &#x60;/notes/01931f40-...&#x60;); Android - the screen name (&#x60;review&#x60;). Clients SHOULD omit the query string and fragment; the server cuts at the first &#x60;?&#x60; or &#x60;#&#x60; anyway. |  [optional] |
| **stack** | **kotlin.String** | Optional stack trace, at most 16384 code points; clients truncate. Absent when unknown. |  [optional] |


<a id="Client"></a>
## Enum: client
| Name | Value |
| ---- | ----- |
| client | web, android |


<a id="Kind"></a>
## Enum: kind
| Name | Value |
| ---- | ----- |
| kind | exception, api_failure, unhandled_rejection, crash |


<a id="Level"></a>
## Enum: level
| Name | Value |
| ---- | ----- |
| level | error, warn |


<a id="HttpMethod"></a>
## Enum: http_method
| Name | Value |
| ---- | ----- |
| httpMethod | GET, HEAD, POST, PUT, PATCH, DELETE |



