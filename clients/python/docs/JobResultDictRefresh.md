# JobResultDictRefresh

Result of a dictionary refresh.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**entries** | **int** | Live entries in the current release. | 
**release_id** | **int** | New release id; null when nothing changed and &#x60;force&#x60; was false. | 
**retired** | **int** | Entries retired by this release. | 
**version** | **str** | New release version. | 

## Example

```python
from kotobaya_client.models.job_result_dict_refresh import JobResultDictRefresh

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultDictRefresh from a JSON string
job_result_dict_refresh_instance = JobResultDictRefresh.from_json(json)
# print the JSON string representation of the object
print(JobResultDictRefresh.to_json())

# convert the object into a dict
job_result_dict_refresh_dict = job_result_dict_refresh_instance.to_dict()
# create an instance of JobResultDictRefresh from a dict
job_result_dict_refresh_from_dict = JobResultDictRefresh.from_dict(job_result_dict_refresh_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


