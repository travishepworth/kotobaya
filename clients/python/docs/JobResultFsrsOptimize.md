# JobResultFsrsOptimize

Result of an optimizer run (`contracts/db/FSRS_PUBLISH.md`). `log_loss_before`, `holdout_review_count` and `reason` were added by C-16 and are not required: results stored before it lack them (treat absent as null). 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**holdout_review_count** | **int** | How many of the &#x60;review_count&#x60; reviews formed the held-out set; 0 when there was none. (C-16) | [optional] 
**log_loss** | **float** | Log loss of the new weights on the held-out reviews. null when no weights were evaluated (&#x60;reason&#x60; &#x60;too_few_reviews&#x60;, &#x60;out_of_bounds&#x60;, or &#x60;preset_deleted&#x60; found before training). | 
**log_loss_before** | **float** | Log loss, on the same held-out reviews, of the weights the preset had when the run started (the built-in defaults for a never-optimized preset). &#x60;published&#x60; is true only if &#x60;log_loss&#x60; &lt; &#x60;log_loss_before&#x60;. null when it was not computed. (C-16) | [optional] 
**preset_id** | **str** | The preset. | 
**published** | **bool** | true iff this run minted an &#x60;fsrs_params.published&#x60; op. false when there were too few reviews or the new weights were not better (see &#x60;reason&#x60;); the preset is then unchanged. | 
**reason** | **str** | Why nothing was published; null iff &#x60;published&#x60; is true. &#x60;too_few_reviews&#x60; &#x3D; below the optimizer&#39;s minimum; &#x60;not_improved&#x60; &#x3D; held-out log loss not strictly lower than before; &#x60;out_of_bounds&#x60; &#x3D; the optimizer produced a weight outside the FSRS-6 bounds; &#x60;preset_deleted&#x60; &#x3D; the preset was deleted before publication. (C-16) | [optional] 
**review_count** | **int** | Reviews used (training and held-out together). | 
**rmse_bins** | **float** | RMSE (bins) of the new weights on the held-out reviews; null exactly when &#x60;log_loss&#x60; is. | 
**weights_version** | **int** | The preset&#39;s &#x60;weights_version&#x60; after the run (when not published, the version the run read). | 

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


