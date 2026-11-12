
# PresetCreate

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **name** | **kotlin.String** | Display name. |  |
| **desiredRetention** | [**java.math.BigDecimal**](java.math.BigDecimal.md) | Target recall probability. |  [optional] |
| **easyDays** | [**kotlin.collections.List&lt;java.math.BigDecimal&gt;**](java.math.BigDecimal.md) | Load multiplier Monday..Sunday. |  [optional] |
| **fuzz** | **kotlin.Boolean** | Apply fuzz and load balancing. |  [optional] |
| **id** | **kotlin.String** | Optional client-generated UUIDv7 (idempotent creation). |  [optional] |
| **learningStepsS** | **kotlin.collections.List&lt;kotlin.Int&gt;** | Learning steps in seconds. |  [optional] |
| **maximumIntervalDays** | **kotlin.Int** | Cap on intervals. |  [optional] |
| **relearningStepsS** | **kotlin.collections.List&lt;kotlin.Int&gt;** | Relearning steps in seconds. |  [optional] |



