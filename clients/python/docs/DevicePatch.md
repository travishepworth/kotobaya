# DevicePatch

Changes to a device. At least one property.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**name** | **str** | New display name. | [optional] 
**push_topic** | **str** | New push endpoint (http/https URL), or null to stop push. | [optional] 

## Example

```python
from kotobaya_client.models.device_patch import DevicePatch

# TODO update the JSON string below
json = "{}"
# create an instance of DevicePatch from a JSON string
device_patch_instance = DevicePatch.from_json(json)
# print the JSON string representation of the object
print(DevicePatch.to_json())

# convert the object into a dict
device_patch_dict = device_patch_instance.to_dict()
# create an instance of DevicePatch from a dict
device_patch_from_dict = DevicePatch.from_dict(device_patch_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


