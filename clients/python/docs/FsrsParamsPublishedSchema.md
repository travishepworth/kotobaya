# FsrsParamsPublishedSchema

The optimizer publishes new FSRS weights for a preset. Applied iff weights_version > the stored one. OPS.md#fsrs_paramspublished

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**fsrs_version** | **str** | FSRS algorithm version the weights belong to. Only &#39;6&#39; (FSRS-6) in op_version 1; a future FSRS version is added additively with its own per-index bounds. | 
**id** | **str** | Id of the preset. Equals the envelope&#39;s entity_id. | 
**optimized_at** | **int** | When the optimizer run finished. | 
**optimizer_stats** | [**FsrsParamsPublishedSchemaOptimizerStats**](FsrsParamsPublishedSchemaOptimizerStats.md) |  | [optional] 
**weights** | **List[object]** | The 21 FSRS-6 weights w[0]..w[20]. Each is bounded (inclusive) by py-fsrs 6.3.2&#39;s LOWER_BOUNDS_PARAMETERS / UPPER_BOUNDS_PARAMETERS; out-of-range weights are rejected (schema_invalid at push, C-1). The bounds give initial stability &gt; 0 and decay w[20] &gt;&#x3D; 0.1; finite schedules follow together with the core&#39;s clamps (T006: STABILITY_MIN, difficulty in [1,10], maximum interval). w[20] is the decay. | 
**weights_version** | **int** | New version, &#x3D; previous + 1. | 

## Example

```python
from kotobaya_client.models.fsrs_params_published_schema import FsrsParamsPublishedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of FsrsParamsPublishedSchema from a JSON string
fsrs_params_published_schema_instance = FsrsParamsPublishedSchema.from_json(json)
# print the JSON string representation of the object
print(FsrsParamsPublishedSchema.to_json())

# convert the object into a dict
fsrs_params_published_schema_dict = fsrs_params_published_schema_instance.to_dict()
# create an instance of FsrsParamsPublishedSchema from a dict
fsrs_params_published_schema_from_dict = FsrsParamsPublishedSchema.from_dict(fsrs_params_published_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


