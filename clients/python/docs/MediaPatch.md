# MediaPatch

Changes to a media. At least one property.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**cover_blob_hash** | **str** | Cover image blob, or null to remove. | [optional] 
**kind** | [**MediaKind**](MediaKind.md) |  | [optional] 
**title** | **str** | Title. | [optional] 

## Example

```python
from kotobaya_client.models.media_patch import MediaPatch

# TODO update the JSON string below
json = "{}"
# create an instance of MediaPatch from a JSON string
media_patch_instance = MediaPatch.from_json(json)
# print the JSON string representation of the object
print(MediaPatch.to_json())

# convert the object into a dict
media_patch_dict = media_patch_instance.to_dict()
# create an instance of MediaPatch from a dict
media_patch_from_dict = MediaPatch.from_dict(media_patch_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


