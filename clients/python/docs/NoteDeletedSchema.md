# NoteDeletedSchema

Tombstones a note (permanent). Hides all its cards. OPS.md#notedeleted

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the note. Equals the envelope&#39;s entity_id. | 

## Example

```python
from kotobaya_client.models.note_deleted_schema import NoteDeletedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of NoteDeletedSchema from a JSON string
note_deleted_schema_instance = NoteDeletedSchema.from_json(json)
# print the JSON string representation of the object
print(NoteDeletedSchema.to_json())

# convert the object into a dict
note_deleted_schema_dict = note_deleted_schema_instance.to_dict()
# create an instance of NoteDeletedSchema from a dict
note_deleted_schema_from_dict = NoteDeletedSchema.from_dict(note_deleted_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


