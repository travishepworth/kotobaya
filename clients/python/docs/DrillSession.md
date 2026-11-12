# DrillSession

An ephemeral drill session.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Fresh UUIDv7; not stored by the server. | 
**items** | [**List[QueueItem]**](QueueItem.md) | Cards to drill, in order (&#x60;preview&#x60; is null). | 
**mode** | **str** | Pass as &#x60;mode&#x60; to &#x60;POST /review/answer&#x60;. | 

## Example

```python
from kotobaya_client.models.drill_session import DrillSession

# TODO update the JSON string below
json = "{}"
# create an instance of DrillSession from a JSON string
drill_session_instance = DrillSession.from_json(json)
# print the JSON string representation of the object
print(DrillSession.to_json())

# convert the object into a dict
drill_session_dict = drill_session_instance.to_dict()
# create an instance of DrillSession from a dict
drill_session_from_dict = DrillSession.from_dict(drill_session_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


