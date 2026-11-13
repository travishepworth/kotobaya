# JobResultGrammarDetectMedia

Result of a per-media `grammar-detect` job. Three shapes: - published: `changed: true`, `content_version` = the version this job published,   `spans`, `invalid_rules`; - nothing to do: `changed: false`, `content_version` = the media's current version   (detected spans already equal the computed ones), `spans`, `invalid_rules`; - skipped: `changed: false`, `skipped` set, no `content_version`, `spans` or   `invalid_rules`. A retry whose earlier attempt already published (MEDIA_PIPELINE.md 3.2 step 1) reports `changed: true` and `content_version` only. 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**changed** | **bool** | True iff this job published a new content version. | 
**content_version** | **int** | Published version (&#x60;changed&#x60;), else the media&#39;s current version. Absent iff &#x60;skipped&#x60;. | [optional] 
**invalid_rules** | **int** | Non-draft rules of live grammar points that failed to compile and were ignored (each is logged with its grammar point id and rule index). | [optional] 
**media_id** | **str** | The media. | 
**skipped** | **str** | Why nothing was detected - no such media; media tombstoned; &#x60;source &#x3D; jpdb&#x60; (no sentences); &#x60;content_version &#x3D; 0&#x60; or no segments. Present iff skipped. | [optional] 
**spans** | **int** | Detected spans the media carries after this job (manual spans not counted). | [optional] 

## Example

```python
from kotobaya_client.models.job_result_grammar_detect_media import JobResultGrammarDetectMedia

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultGrammarDetectMedia from a JSON string
job_result_grammar_detect_media_instance = JobResultGrammarDetectMedia.from_json(json)
# print the JSON string representation of the object
print(JobResultGrammarDetectMedia.to_json())

# convert the object into a dict
job_result_grammar_detect_media_dict = job_result_grammar_detect_media_instance.to_dict()
# create an instance of JobResultGrammarDetectMedia from a dict
job_result_grammar_detect_media_from_dict = JobResultGrammarDetectMedia.from_dict(job_result_grammar_detect_media_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


