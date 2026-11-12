# FsrsParamsPublishedSchemaOptimizerStats

Metrics of the run; unknown keys preserved. Default null.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**log_loss** | **float** |  | [optional] 
**review_count** | **int** |  | [optional] 
**rmse_bins** | **float** |  | [optional] 

## Example

```python
from kotobaya_client.models.fsrs_params_published_schema_optimizer_stats import FsrsParamsPublishedSchemaOptimizerStats

# TODO update the JSON string below
json = "{}"
# create an instance of FsrsParamsPublishedSchemaOptimizerStats from a JSON string
fsrs_params_published_schema_optimizer_stats_instance = FsrsParamsPublishedSchemaOptimizerStats.from_json(json)
# print the JSON string representation of the object
print(FsrsParamsPublishedSchemaOptimizerStats.to_json())

# convert the object into a dict
fsrs_params_published_schema_optimizer_stats_dict = fsrs_params_published_schema_optimizer_stats_instance.to_dict()
# create an instance of FsrsParamsPublishedSchemaOptimizerStats from a dict
fsrs_params_published_schema_optimizer_stats_from_dict = FsrsParamsPublishedSchemaOptimizerStats.from_dict(fsrs_params_published_schema_optimizer_stats_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


