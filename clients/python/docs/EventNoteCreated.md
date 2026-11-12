# EventNoteCreated

Payload of `note.created`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**note_id** | **str** | The new note (may already be a merge loser; &#x60;GET /notes/{id}&#x60; answers with the survivor). | 
**origin** | **str** | The note&#39;s &#x60;origin&#x60;. | 
**origin_device_id** | **str** | Device whose op created it. | 
**seq** | **int** | &#x60;change.seq&#x60; of the creating op. | 

## Example

```python
from kotobaya_client.models.event_note_created import EventNoteCreated

# TODO update the JSON string below
json = "{}"
# create an instance of EventNoteCreated from a JSON string
event_note_created_instance = EventNoteCreated.from_json(json)
# print the JSON string representation of the object
print(EventNoteCreated.to_json())

# convert the object into a dict
event_note_created_dict = event_note_created_instance.to_dict()
# create an instance of EventNoteCreated from a dict
event_note_created_from_dict = EventNoteCreated.from_dict(event_note_created_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


