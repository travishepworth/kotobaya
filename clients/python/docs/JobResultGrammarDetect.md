# JobResultGrammarDetect

Result of a `grammar-detect` job (C-13). Per-media jobs (payload has `media_id`) report `JobResultGrammarDetectMedia`; fan-out jobs (payload `{}`) report `JobResultGrammarDetectFanOut`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**changed** | **bool** | True iff this job published a new content version. | 
**content_version** | **int** | Published version (&#x60;changed&#x60;), else the media&#39;s current version. Absent iff &#x60;skipped&#x60;. | [optional] 
**invalid_rules** | **int** | Non-draft rules of live grammar points that failed to compile and were ignored (each is logged with its grammar point id and rule index). | [optional] 
**media_id** | **str** | The media. | 
**skipped** | **str** | Why nothing was detected - no such media; media tombstoned; &#x60;source &#x3D; jpdb&#x60; (no sentences); &#x60;content_version &#x3D; 0&#x60; or no segments. Present iff skipped. | [optional] 
**spans** | **int** | Detected spans the media carries after this job (manual spans not counted). | [optional] 
**enqueued** | **int** | Per-media jobs actually inserted (&#x60;&lt;&#x3D; media&#x60;); the rest collapsed into an identical queued or running job (same dedupe key). | 
**media** | **int** | Eligible media (live, &#x60;content_version &gt; 0&#x60;, &#x60;source&#x60; not &#x60;jpdb&#x60;). | 
**rules_digest** | **str** | Opaque worker-defined digest of the live points&#39; detection rules read by this job; compare for equality only. Part of the per-media dedupe keys. | 

## Example

```python
from kotobaya_client.models.job_result_grammar_detect import JobResultGrammarDetect

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultGrammarDetect from a JSON string
job_result_grammar_detect_instance = JobResultGrammarDetect.from_json(json)
# print the JSON string representation of the object
print(JobResultGrammarDetect.to_json())

# convert the object into a dict
job_result_grammar_detect_dict = job_result_grammar_detect_instance.to_dict()
# create an instance of JobResultGrammarDetect from a dict
job_result_grammar_detect_from_dict = JobResultGrammarDetect.from_dict(job_result_grammar_detect_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


