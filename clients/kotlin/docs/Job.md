
# Job

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **attempts** | **kotlin.Int** | Attempts started so far. |  |
| **createdAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | When it was queued. |  |
| **error** | **kotlin.String** | Error text of the last failed attempt. |  |
| **finishedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Set iff the status is terminal. |  |
| **id** | **kotlin.String** | Job id (server-generated UUIDv7). |  |
| **kind** | [**JobKind**](JobKind.md) |  |  |
| **maxAttempts** | **kotlin.Int** | Attempts allowed before the job is &#x60;failed&#x60;. |  |
| **payload** | [**kotlin.collections.Map&lt;kotlin.String, kotlin.Any&gt;**](kotlin.Any.md) | Input of the job; schema by &#x60;kind&#x60;. |  |
| **progress** | [**java.math.BigDecimal**](java.math.BigDecimal.md) | Fraction done; 1 when succeeded. |  |
| **progressMessage** | **kotlin.String** | Short human-readable stage, e.g. &#x60;parsing ep02.srt (2/12)&#x60;. |  |
| **result** | [**kotlin.collections.Map&lt;kotlin.String, kotlin.Any&gt;**](kotlin.Any.md) | Output when &#x60;succeeded&#x60; (schema by &#x60;kind&#x60;); may hold partial counts when &#x60;failed&#x60; or &#x60;cancelled&#x60;; null before. |  |
| **startedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Start of the current or last attempt. |  |
| **status** | [**JobStatus**](JobStatus.md) |  |  |



