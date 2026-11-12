# JobResultMediaUnmappedInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**jpdb_vid** | **int** | jpdb vocabulary id. | 
**occurrence_count** | **int** | Occurrences in the deck. | 
**reading** | **str** | Reading as jpdb gives it. | 
**spelling** | **str** | Spelling as jpdb gives it. | 

## Example

```python
from kotobaya_client.models.job_result_media_unmapped_inner import JobResultMediaUnmappedInner

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultMediaUnmappedInner from a JSON string
job_result_media_unmapped_inner_instance = JobResultMediaUnmappedInner.from_json(json)
# print the JSON string representation of the object
print(JobResultMediaUnmappedInner.to_json())

# convert the object into a dict
job_result_media_unmapped_inner_dict = job_result_media_unmapped_inner_instance.to_dict()
# create an instance of JobResultMediaUnmappedInner from a dict
job_result_media_unmapped_inner_from_dict = JobResultMediaUnmappedInner.from_dict(job_result_media_unmapped_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


