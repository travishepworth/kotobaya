# EventSyncChanged

Payload of `sync.changed`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**high_water_seq** | **int** | New sync head. | 

## Example

```python
from kotobaya_client.models.event_sync_changed import EventSyncChanged

# TODO update the JSON string below
json = "{}"
# create an instance of EventSyncChanged from a JSON string
event_sync_changed_instance = EventSyncChanged.from_json(json)
# print the JSON string representation of the object
print(EventSyncChanged.to_json())

# convert the object into a dict
event_sync_changed_dict = event_sync_changed_instance.to_dict()
# create an instance of EventSyncChanged from a dict
event_sync_changed_from_dict = EventSyncChanged.from_dict(event_sync_changed_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


