
# Preset

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **createdAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Creation time. |  |
| **deletedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Tombstone. |  |
| **desiredRetention** | [**java.math.BigDecimal**](java.math.BigDecimal.md) | Target recall probability. |  |
| **easyDays** | [**kotlin.collections.List&lt;java.math.BigDecimal&gt;**](java.math.BigDecimal.md) | Load multiplier per ISO weekday, element 0 &#x3D; Monday ... 6 &#x3D; Sunday (the SQL array index 1..7). 1 &#x3D; normal, 0 &#x3D; minimum. |  |
| **fsrsVersion** | **kotlin.String** | FSRS version of &#x60;weights&#x60;; null together with &#x60;weights&#x60;. |  |
| **fuzz** | **kotlin.Boolean** | Whether interval fuzz and load balancing are applied. |  |
| **id** | **kotlin.String** | Preset id. &#x60;00000000-0000-7000-8000-000000000010&#x60; is the Default preset. |  |
| **learningStepsS** | **kotlin.collections.List&lt;kotlin.Int&gt;** | Learning steps in seconds, in order; empty &#x3D; none. |  |
| **maximumIntervalDays** | **kotlin.Int** | Cap on scheduled intervals. |  |
| **name** | **kotlin.String** | Display name. |  |
| **optimizedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | When weights were last published. |  |
| **optimizerStats** | [**kotlin.collections.Map&lt;kotlin.String, kotlin.Any&gt;**](kotlin.Any.md) | Metrics of the last published optimizer run: &#x60;{\&quot;review_count\&quot;, \&quot;log_loss\&quot;, \&quot;rmse_bins\&quot;}&#x60; and, since C-16, &#x60;\&quot;log_loss_before\&quot;&#x60; (held-out log loss of the weights it replaced) and &#x60;\&quot;holdout_review_count\&quot;&#x60;; each key may be absent. |  |
| **relearningStepsS** | **kotlin.collections.List&lt;kotlin.Int&gt;** | Relearning steps in seconds. |  |
| **updatedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Last change. |  |
| **weights** | [**kotlin.collections.List&lt;java.math.BigDecimal&gt;**](java.math.BigDecimal.md) | Optimized FSRS weights, or null &#x3D; the core&#39;s defaults. |  |
| **weightsVersion** | **kotlin.Int** | Incremented by each optimizer publication; 0 &#x3D; never optimized. |  |



