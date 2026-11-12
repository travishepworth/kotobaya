# Media

A media (`app.media`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**content_blob_hash** | **str** | Snapshot archive for &#x60;content_version&#x60;. | 
**content_version** | **int** | Bumped by each published (re-)parse; 0 &#x3D; nothing published. | 
**cover_blob_hash** | **str** | Cover image blob. | 
**created_at** | **datetime** | Creation time. | 
**deleted_at** | **datetime** | Tombstone. | 
**id** | **str** | Media id; also the id of its deck. | 
**jpdb_id** | **str** | jpdb deck id when &#x60;source&#x60; is &#x60;jpdb&#x60;. | 
**kind** | [**MediaKind**](MediaKind.md) |  | 
**language** | **str** | BCP 47; always &#x60;ja&#x60; in v1. | 
**parse_error** | **str** | Error text when &#x60;parse_status&#x60; is &#x60;failed&#x60;. | 
**parse_status** | **str** | Server-owned parse state. | 
**segments** | [**List[MediaSegment]**](MediaSegment.md) | Segments in &#x60;ordinal&#x60; order. Present on &#x60;GET /media/{media_id}&#x60; and &#x60;PATCH&#x60;; omitted in lists. | [optional] 
**source** | **str** | How it was imported. Immutable. | 
**title** | **str** | Title. | 
**updated_at** | **datetime** | Last change. | 

## Example

```python
from kotobaya_client.models.media import Media

# TODO update the JSON string below
json = "{}"
# create an instance of Media from a JSON string
media_instance = Media.from_json(json)
# print the JSON string representation of the object
print(Media.to_json())

# convert the object into a dict
media_dict = media_instance.to_dict()
# create an instance of Media from a dict
media_from_dict = Media.from_dict(media_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


