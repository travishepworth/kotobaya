# JobPayloadFsrsOptimize

Payload of an `fsrs-optimize` job.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**preset_id** | **str** | Preset to optimize. | 

## Example

```python
from kotobaya_client.models.job_payload_fsrs_optimize import JobPayloadFsrsOptimize

# TODO update the JSON string below
json = "{}"
# create an instance of JobPayloadFsrsOptimize from a JSON string
job_payload_fsrs_optimize_instance = JobPayloadFsrsOptimize.from_json(json)
# print the JSON string representation of the object
print(JobPayloadFsrsOptimize.to_json())

# convert the object into a dict
job_payload_fsrs_optimize_dict = job_payload_fsrs_optimize_instance.to_dict()
# create an instance of JobPayloadFsrsOptimize from a dict
job_payload_fsrs_optimize_from_dict = JobPayloadFsrsOptimize.from_dict(job_payload_fsrs_optimize_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


