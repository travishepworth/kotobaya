# JobResultFsrsOptimize

Result of an optimizer run.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**log_loss** | **float** | Log loss of the new weights. | 
**preset_id** | **str** | The preset. | 
**published** | **bool** | false when there were too few reviews or the new weights were not better; the preset is then unchanged. | 
**review_count** | **int** | Reviews used. | 
**rmse_bins** | **float** | RMSE (bins) of the new weights. | 
**weights_version** | **int** | The preset&#39;s &#x60;weights_version&#x60; after the run. | 

## Example

```python
from kotobaya_client.models.job_result_fsrs_optimize import JobResultFsrsOptimize

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultFsrsOptimize from a JSON string
job_result_fsrs_optimize_instance = JobResultFsrsOptimize.from_json(json)
# print the JSON string representation of the object
print(JobResultFsrsOptimize.to_json())

# convert the object into a dict
job_result_fsrs_optimize_dict = job_result_fsrs_optimize_instance.to_dict()
# create an instance of JobResultFsrsOptimize from a dict
job_result_fsrs_optimize_from_dict = JobResultFsrsOptimize.from_dict(job_result_fsrs_optimize_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


