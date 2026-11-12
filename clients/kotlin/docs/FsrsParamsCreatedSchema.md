
# FsrsParamscreatedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **id** | **kotlin.String** | Id of the new preset. Equals the envelope&#39;s entity_id. |  |
| **name** | **kotlin.String** | Register &#39;name&#39;. |  |
| **desiredRetention** | [**java.math.BigDecimal**](java.math.BigDecimal.md) | Register &#39;desired_retention&#39;. Column default 0.9. |  [optional] |
| **easyDays** | [**kotlin.collections.List&lt;java.math.BigDecimal&gt;**](java.math.BigDecimal.md) | Register &#39;easy_days&#39;: load multiplier per ISO weekday, Monday first. Column default [1,1,1,1,1,1,1]. |  [optional] |
| **fuzz** | **kotlin.Boolean** | Register &#39;fuzz&#39;. Column default true. |  [optional] |
| **learningStepsS** | **kotlin.collections.List&lt;kotlin.Int&gt;** | Register &#39;learning_steps_s&#39;: steps in seconds, in order; [] &#x3D; none. |  [optional] |
| **maximumIntervalDays** | **kotlin.Int** | Register &#39;maximum_interval_days&#39;. Column default 36500. At most 36500 (C-6, &#x3D; the API bound): it caps every derived interval, so card.due_at stays within Postgres timestamptz whatever desired_retention is (SYNC.md 2.1 storable values). |  [optional] |
| **relearningStepsS** | **kotlin.collections.List&lt;kotlin.Int&gt;** | Register &#39;relearning_steps_s&#39;: steps in seconds, in order; [] &#x3D; none. |  [optional] |



