# EventHello

Payload of `hello`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**high_water_seq** | **int** | Current sync head (as &#x60;PullResponse.high_water_seq&#x60;). | 
**log_epoch** | **str** | Current log identity (contracts/sync/SYNC.md 6.2). | 
**server_time** | **datetime** | Server wall clock. | 

## Example

```python
from kotobaya_client.models.event_hello import EventHello

# TODO update the JSON string below
json = "{}"
# create an instance of EventHello from a JSON string
event_hello_instance = EventHello.from_json(json)
# print the JSON string representation of the object
print(EventHello.to_json())

# convert the object into a dict
event_hello_dict = event_hello_instance.to_dict()
# create an instance of EventHello from a dict
event_hello_from_dict = EventHello.from_dict(event_hello_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


