# EventDevice

Payload of `device.paired` and `device.revoked`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**device_id** | **str** | The device. | 
**name** | **str** | Its name. | 

## Example

```python
from kotobaya_client.models.event_device import EventDevice

# TODO update the JSON string below
json = "{}"
# create an instance of EventDevice from a JSON string
event_device_instance = EventDevice.from_json(json)
# print the JSON string representation of the object
print(EventDevice.to_json())

# convert the object into a dict
event_device_dict = event_device_instance.to_dict()
# create an instance of EventDevice from a dict
event_device_from_dict = EventDevice.from_dict(event_device_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


