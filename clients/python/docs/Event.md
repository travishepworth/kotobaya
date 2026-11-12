# Event

One event, as the JSON of an SSE `data:` line and as an ntfy message body.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**at** | **datetime** | When the event was produced. | 
**data** | **Dict[str, Optional[object]]** | Payload; schema by &#x60;type&#x60; (table on &#x60;GET /events&#x60;). | 
**type** | [**EventType**](EventType.md) |  | 

## Example

```python
from kotobaya_client.models.event import Event

# TODO update the JSON string below
json = "{}"
# create an instance of Event from a JSON string
event_instance = Event.from_json(json)
# print the JSON string representation of the object
print(Event.to_json())

# convert the object into a dict
event_dict = event_instance.to_dict()
# create an instance of Event from a dict
event_from_dict = Event.from_dict(event_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


