# JobResultMedia

Result of a media import, jpdb deck import or parse.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**content_version** | **int** | Content version published by this job. | 
**media_id** | **str** | The media. | 
**parser_version** | **str** | Optional (C-12). The parser build that produced the published tokens (PARSER.md 6). Absent until the media content archive header carries it (follow-up for SNAPSHOT.md); always absent for jpdb imports. | [optional] 
**segments** | **int** | Segments now in the media. | 
**sentences** | **int** | Sentences now in the media. | 
**tokens** | **int** | Word tokens. | 
**unique_entries** | **int** | Distinct entries (&#x3D; deck items). | 
**unmapped** | [**List[JobResultMediaUnmappedInner]**](JobResultMediaUnmappedInner.md) | jpdb deck import only - vocabulary that maps to no JMdict entry and has no stored correction (&#x60;listJpdbVocab&#x60; status &#x60;unresolved&#x60;), by &#x60;occurrence_count&#x60; descending then &#x60;jpdb_vid&#x60;; empty otherwise. Items the user marked &#x60;ignored&#x60; are not listed but their tokens still count in &#x60;unmapped_tokens&#x60;. Nothing is dropped silently (C-11). | 
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


