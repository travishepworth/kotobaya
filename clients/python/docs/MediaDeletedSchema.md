# MediaDeletedSchema

Tombstones a media (permanent); bulk content is hard-deleted and the server tombstones its deck. OPS.md#mediadeleted

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the media. Equals the envelope&#39;s entity_id. | 

## Example

```python
from kotobaya_client.models.media_deleted_schema import MediaDeletedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of MediaDeletedSchema from a JSON string
media_deleted_schema_instance = MediaDeletedSchema.from_json(json)
# print the JSON string representation of the object
print(MediaDeletedSchema.to_json())

# convert the object into a dict
media_deleted_schema_dict = media_deleted_schema_instance.to_dict()
# create an instance of MediaDeletedSchema from a dict
media_deleted_schema_from_dict = MediaDeletedSchema.from_dict(media_deleted_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


