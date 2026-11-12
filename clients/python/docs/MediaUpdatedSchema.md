# MediaUpdatedSchema

Sets a subset of a media's user registers. OPS.md#mediaupdated

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the media. Equals the envelope&#39;s entity_id. | 
**set** | [**MediaUpdatedSchemaSet**](MediaUpdatedSchemaSet.md) |  | 

## Example

```python
from kotobaya_client.models.media_updated_schema import MediaUpdatedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of MediaUpdatedSchema from a JSON string
media_updated_schema_instance = MediaUpdatedSchema.from_json(json)
# print the JSON string representation of the object
print(MediaUpdatedSchema.to_json())

# convert the object into a dict
media_updated_schema_dict = media_updated_schema_instance.to_dict()
# create an instance of MediaUpdatedSchema from a dict
media_updated_schema_from_dict = MediaUpdatedSchema.from_dict(media_updated_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


