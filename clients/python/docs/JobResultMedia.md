# JobResultMedia

Result of a media import, jpdb deck import or parse.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**content_version** | **int** | Content version published by this job. | 
**media_id** | **str** | The media. | 
**segments** | **int** | Segments now in the media. | 
**sentences** | **int** | Sentences now in the media. | 
**tokens** | **int** | Word tokens. | 
**unique_entries** | **int** | Distinct entries (&#x3D; deck items). | 
**unmapped** | [**List[JobResultMediaUnmappedInner]**](JobResultMediaUnmappedInner.md) | jpdb deck import only - vocabulary that maps to no JMdict entry (empty otherwise). Nothing is dropped silently. | 
**unmapped_tokens** | **int** | Tokens that matched no entry. | 

## Example

```python
from kotobaya_client.models.job_result_media import JobResultMedia

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultMedia from a JSON string
job_result_media_instance = JobResultMedia.from_json(json)
# print the JSON string representation of the object
print(JobResultMedia.to_json())

# convert the object into a dict
job_result_media_dict = job_result_media_instance.to_dict()
# create an instance of JobResultMedia from a dict
job_result_media_from_dict = JobResultMedia.from_dict(job_result_media_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


