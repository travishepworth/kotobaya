# DeviceList

All devices.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[Device]**](Device.md) | Devices ordered by &#x60;paired_at&#x60;. | 

## Example

```python
from kotobaya_client.models.device_list import DeviceList

# TODO update the JSON string below
json = "{}"
# create an instance of DeviceList from a JSON string
device_list_instance = DeviceList.from_json(json)
# print the JSON string representation of the object
print(DeviceList.to_json())

# convert the object into a dict
device_list_dict = device_list_instance.to_dict()
# create an instance of DeviceList from a dict
device_list_from_dict = DeviceList.from_dict(device_list_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


