# MediaContentPublishedSchema

Server publishes parse progress and, when parsed, a new content snapshot. OPS.md#mediacontent_published

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**content_blob_hash** | **str** | Snapshot blob for content_version; null iff content_version &#x3D; 0. | 
**content_version** | **int** | content_version after this op; 0 &#x3D; nothing published yet. Applied iff &gt;&#x3D; the stored one. | 
**media_id** | **str** | Media. Equals entity_id. | 
**parse_error** | **str** | Error text when parse_status &#x3D; failed, else null. Default null. | [optional] 
**parse_status** | **str** | New parse_status. | 

## Example

```python
from kotobaya_client.models.media_content_published_schema import MediaContentPublishedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of MediaContentPublishedSchema from a JSON string
media_content_published_schema_instance = MediaContentPublishedSchema.from_json(json)
# print the JSON string representation of the object
print(MediaContentPublishedSchema.to_json())

# convert the object into a dict
media_content_published_schema_dict = media_content_published_schema_instance.to_dict()
# create an instance of MediaContentPublishedSchema from a dict
media_content_published_schema_from_dict = MediaContentPublishedSchema.from_dict(media_content_published_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


