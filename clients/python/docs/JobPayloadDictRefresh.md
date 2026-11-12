# JobPayloadDictRefresh

Options of a dictionary refresh.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**force** | **bool** | Build a new release even if no source changed. | [optional] [default to False]
**freq_lists** | **List[str]** | Frequency list slugs to load, in priority order (first &#x3D; primary). Default - the server&#39;s configured lists. | [optional] 

## Example

```python
from kotobaya_client.models.job_payload_dict_refresh import JobPayloadDictRefresh

# TODO update the JSON string below
json = "{}"
# create an instance of JobPayloadDictRefresh from a JSON string
job_payload_dict_refresh_instance = JobPayloadDictRefresh.from_json(json)
# print the JSON string representation of the object
print(JobPayloadDictRefresh.to_json())

# convert the object into a dict
job_payload_dict_refresh_dict = job_payload_dict_refresh_instance.to_dict()
# create an instance of JobPayloadDictRefresh from a dict
job_payload_dict_refresh_from_dict = JobPayloadDictRefresh.from_dict(job_payload_dict_refresh_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


