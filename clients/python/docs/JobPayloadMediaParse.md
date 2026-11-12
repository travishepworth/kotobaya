# JobPayloadMediaParse

Payload of a `media-parse` job.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**media_id** | **str** | Media to re-parse. | 

## Example

```python
from kotobaya_client.models.job_payload_media_parse import JobPayloadMediaParse

# TODO update the JSON string below
json = "{}"
# create an instance of JobPayloadMediaParse from a JSON string
job_payload_media_parse_instance = JobPayloadMediaParse.from_json(json)
# print the JSON string representation of the object
print(JobPayloadMediaParse.to_json())

# convert the object into a dict
job_payload_media_parse_dict = job_payload_media_parse_instance.to_dict()
# create an instance of JobPayloadMediaParse from a dict
job_payload_media_parse_from_dict = JobPayloadMediaParse.from_dict(job_payload_media_parse_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


