# MediaCreatedSchema

An import job created a media row. parse_status starts 'pending', content_version 0. OPS.md#mediacreated

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**cover_blob_hash** | **str** | Register &#39;cover_blob_hash&#39;; null &#x3D; no cover. | [optional] 
**id** | **str** | Id of the new media. Equals the envelope&#39;s entity_id. | 
**jpdb_id** | **str** | jpdb deck id when source &#x3D; jpdb. Immutable. Default null. At most 200 code points (C-6: unique btree index media_live_jpdb_uq). | [optional] 
**kind** | **str** | Register &#39;kind&#39;. | 
**source** | **str** | Immutable. | 
**title** | **str** | Register &#39;title&#39;. | 

## Example

```python
from kotobaya_client.models.media_created_schema import MediaCreatedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of MediaCreatedSchema from a JSON string
media_created_schema_instance = MediaCreatedSchema.from_json(json)
# print the JSON string representation of the object
print(MediaCreatedSchema.to_json())

# convert the object into a dict
media_created_schema_dict = media_created_schema_instance.to_dict()
# create an instance of MediaCreatedSchema from a dict
media_created_schema_from_dict = MediaCreatedSchema.from_dict(media_created_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


