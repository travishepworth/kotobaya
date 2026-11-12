# MediaUpdatedSchemaSet

Registers to write. At least one.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**cover_blob_hash** | **str** | Register &#39;cover_blob_hash&#39;; null &#x3D; no cover. | [optional] 
**kind** | **str** | Register &#39;kind&#39;. | [optional] 
**title** | **str** | Register &#39;title&#39;. | [optional] 

## Example

```python
from kotobaya_client.models.media_updated_schema_set import MediaUpdatedSchemaSet

# TODO update the JSON string below
json = "{}"
# create an instance of MediaUpdatedSchemaSet from a JSON string
media_updated_schema_set_instance = MediaUpdatedSchemaSet.from_json(json)
# print the JSON string representation of the object
print(MediaUpdatedSchemaSet.to_json())

# convert the object into a dict
media_updated_schema_set_dict = media_updated_schema_set_instance.to_dict()
# create an instance of MediaUpdatedSchemaSet from a dict
media_updated_schema_set_from_dict = MediaUpdatedSchemaSet.from_dict(media_updated_schema_set_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


