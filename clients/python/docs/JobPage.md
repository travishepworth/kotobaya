# JobPage

A page of jobs.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[Job]**](Job.md) | Jobs, newest first. | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.job_page import JobPage

# TODO update the JSON string below
json = "{}"
# create an instance of JobPage from a JSON string
job_page_instance = JobPage.from_json(json)
# print the JSON string representation of the object
print(JobPage.to_json())

# convert the object into a dict
job_page_dict = job_page_instance.to_dict()
# create an instance of JobPage from a dict
job_page_from_dict = JobPage.from_dict(job_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


