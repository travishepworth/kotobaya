
# ReviewLog

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **button** | [**ReviewButton**](ReviewButton.md) | Button pressed; null when unknown or manual. |  |
| **cardId** | **kotlin.String** | Card as addressed when the review was logged (never rewritten after merges). |  |
| **deviceId** | **kotlin.String** | Device that logged it; the server device for REST reviews and imports. |  |
| **durationMs** | **kotlin.Int** | Time from card shown to answer. |  |
| **hlc** | **kotlin.String** | Replay position. |  |
| **id** | **kotlin.String** | Review id. |  |
| **importSource** | **kotlin.String** | &#x60;jpdb&#x60; for imported history; null for native reviews. |  |
| **manualAction** | [**inline**](#ManualAction) | For &#x60;manual&#x60; rows. |  |
| **manualDueAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | For &#x60;set_due&#x60;. |  |
| **rating** | **kotlin.Int** | 1 Again, 2 Hard, 3 Good, 4 Easy; null iff &#x60;review_type&#x60; is &#x60;manual&#x60;. |  |
| **reviewType** | [**ReviewType**](ReviewType.md) |  |  |
| **reviewedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Wall-clock time of the review. |  |
| **sentenceId** | **kotlin.String** | Example sentence shown (rotating-example cards). |  |
| **undoneAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Set when the review was undone; undone rows are ignored by replay and stats. |  |
| **utcOffsetMin** | **kotlin.Int** | Reviewer&#39;s UTC offset in minutes; null &#x3D; unknown. |  |


<a id="ManualAction"></a>
## Enum: manual_action
| Name | Value |
| ---- | ----- |
| manualAction | set_due, reset,  |



