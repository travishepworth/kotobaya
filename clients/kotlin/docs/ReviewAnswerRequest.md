
# ReviewAnswerRequest

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **cardId** | **kotlin.String** | The card answered. |  |
| **id** | **kotlin.String** | Client-generated UUIDv7 of the review (&#x60;review_log.id&#x60;); minted once per answer and reused on retry, which makes the call idempotent. |  |
| **rating** | **kotlin.Int** | 1 Again, 2 Hard, 3 Good, 4 Easy. |  |
| **button** | [**ReviewButton**](ReviewButton.md) | Button pressed; must map to &#x60;rating&#x60;. |  [optional] |
| **durationMs** | **kotlin.Int** | Time from card shown to answer; clamped to 600000. |  [optional] |
| **mode** | [**inline**](#Mode) | &#x60;schedule&#x60; feeds FSRS; &#x60;drill&#x60; and &#x60;cram&#x60; only log. |  [optional] |
| **reviewedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | When the answer was given. Default - now. Must be at most 5 minutes after now, not before 2020-01-01, and not more than 24 h before &#x60;min(card.created_at, now)&#x60;; otherwise &#x60;422&#x60; (see &#x60;answerReview&#x60;). |  [optional] |
| **sentenceId** | **kotlin.String** | The example sentence that was shown, from the queue item. |  [optional] |
| **utcOffsetMin** | **kotlin.Int** | Reviewer&#39;s UTC offset in minutes (540 &#x3D; JST). |  [optional] |


<a id="Mode"></a>
## Enum: mode
| Name | Value |
| ---- | ----- |
| mode | schedule, drill, cram |



