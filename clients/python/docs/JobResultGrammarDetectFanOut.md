# JobResultGrammarDetectFanOut

Result of a fan-out `grammar-detect` job.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**enqueued** | **int** | Per-media jobs actually inserted (&#x60;&lt;&#x3D; media&#x60;); the rest collapsed into an identical queued or running job (same dedupe key). | 
**media** | **int** | Eligible media (live, &#x60;content_version &gt; 0&#x60;, &#x60;source&#x60; not &#x60;jpdb&#x60;). | 
**rules_digest** | **str** | Opaque worker-defined digest of the live points&#39; detection rules read by this job; compare for equality only. Part of the per-media dedupe keys. | 

## Example

```python
from kotobaya_client.models.job_result_grammar_detect_fan_out import JobResultGrammarDetectFanOut

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultGrammarDetectFanOut from a JSON string
job_result_grammar_detect_fan_out_instance = JobResultGrammarDetectFanOut.from_json(json)
# print the JSON string representation of the object
print(JobResultGrammarDetectFanOut.to_json())

# convert the object into a dict
job_result_grammar_detect_fan_out_dict = job_result_grammar_detect_fan_out_instance.to_dict()
# create an instance of JobResultGrammarDetectFanOut from a dict
job_result_grammar_detect_fan_out_from_dict = JobResultGrammarDetectFanOut.from_dict(job_result_grammar_detect_fan_out_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


