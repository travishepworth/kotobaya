# JobPayloadGrammarDetect

Payload of a `grammar-detect` job (C-13). `{}` = fan-out: enqueue one per-media job for every eligible media. `{\"media_id\": …}` = re-detect that one media. Enqueued by the server (fan-out) and the worker (per-media), never through the API. 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**media_id** | **str** | Media to re-detect. Absent &#x3D; fan-out over all eligible media. | [optional] 

## Example

```python
from kotobaya_client.models.job_payload_grammar_detect import JobPayloadGrammarDetect

# TODO update the JSON string below
json = "{}"
# create an instance of JobPayloadGrammarDetect from a JSON string
job_payload_grammar_detect_instance = JobPayloadGrammarDetect.from_json(json)
# print the JSON string representation of the object
print(JobPayloadGrammarDetect.to_json())

# convert the object into a dict
job_payload_grammar_detect_dict = job_payload_grammar_detect_instance.to_dict()
# create an instance of JobPayloadGrammarDetect from a dict
job_payload_grammar_detect_from_dict = JobPayloadGrammarDetect.from_dict(job_payload_grammar_detect_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


