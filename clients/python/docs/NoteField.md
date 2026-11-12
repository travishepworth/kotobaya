# NoteField

One field definition of a note type.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**key** | **str** | Field key, unique within the type, never renamed. | 
**kind** | **str** | What the field holds. | 
**label** | **str** | Display label. | 

## Example

```python
from kotobaya_client.models.note_field import NoteField

# TODO update the JSON string below
json = "{}"
# create an instance of NoteField from a JSON string
note_field_instance = NoteField.from_json(json)
# print the JSON string representation of the object
print(NoteField.to_json())

# convert the object into a dict
note_field_dict = note_field_instance.to_dict()
# create an instance of NoteField from a dict
note_field_from_dict = NoteField.from_dict(note_field_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


