# JobResultDeckBuild

Result of a deck build.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**content_version** | **int** | Content version published. | 
**deck_id** | **str** | The deck. | 
**items** | **int** | Items in the snapshot. | 

## Example

```python
from kotobaya_client.models.job_result_deck_build import JobResultDeckBuild

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultDeckBuild from a JSON string
job_result_deck_build_instance = JobResultDeckBuild.from_json(json)
# print the JSON string representation of the object
print(JobResultDeckBuild.to_json())

# convert the object into a dict
job_result_deck_build_dict = job_result_deck_build_instance.to_dict()
# create an instance of JobResultDeckBuild from a dict
job_result_deck_build_from_dict = JobResultDeckBuild.from_dict(job_result_deck_build_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


