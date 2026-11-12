
# FsrsParamspublishedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **fsrsVersion** | [**inline**](#FsrsVersion) | FSRS algorithm version the weights belong to. Only &#39;6&#39; (FSRS-6) in op_version 1; a future FSRS version is added additively with its own per-index bounds. |  |
| **id** | **kotlin.String** | Id of the preset. Equals the envelope&#39;s entity_id. |  |
| **optimizedAt** | **kotlin.Int** | When the optimizer run finished. |  |
| **weights** | [**kotlin.collections.List&lt;kotlin.Any&gt;**](kotlin.Any.md) | The 21 FSRS-6 weights w[0]..w[20]. Each is bounded (inclusive) by py-fsrs 6.3.2&#39;s LOWER_BOUNDS_PARAMETERS / UPPER_BOUNDS_PARAMETERS; out-of-range weights are rejected (schema_invalid at push, C-1). The bounds give initial stability &gt; 0 and decay w[20] &gt;&#x3D; 0.1; finite schedules follow together with the core&#39;s clamps (T006: STABILITY_MIN, difficulty in [1,10], maximum interval). w[20] is the decay. |  |
| **weightsVersion** | **kotlin.Int** | New version, &#x3D; previous + 1. |  |
| **optimizerStats** | [**FsrsParamsPublishedSchemaOptimizerStats**](FsrsParamsPublishedSchemaOptimizerStats.md) |  |  [optional] |


<a id="FsrsVersion"></a>
## Enum: fsrs_version
| Name | Value |
| ---- | ----- |
| fsrsVersion | 6 |



