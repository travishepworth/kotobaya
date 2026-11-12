# JobResultTts

Result of a TTS job.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**blob_hash** | **str** | The synthesized audio. | 
**content_type** | **str** | Media type of the audio. | 
**duration_ms** | **int** | Length of the audio. | 

## Example

```python
from kotobaya_client.models.job_result_tts import JobResultTts

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultTts from a JSON string
job_result_tts_instance = JobResultTts.from_json(json)
# print the JSON string representation of the object
print(JobResultTts.to_json())

# convert the object into a dict
job_result_tts_dict = job_result_tts_instance.to_dict()
# create an instance of JobResultTts from a dict
job_result_tts_from_dict = JobResultTts.from_dict(job_result_tts_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


