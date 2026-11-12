# NoteTypeDeletedSchema

Tombstones a user-defined note type. Rejected for built-ins. OPS.md#note_typedeleted

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the note type. Equals the envelope&#39;s entity_id. | 

## Example

```python
from kotobaya_client.models.note_type_deleted_schema import NoteTypeDeletedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of NoteTypeDeletedSchema from a JSON string
note_type_deleted_schema_instance = NoteTypeDeletedSchema.from_json(json)
# print the JSON string representation of the object
print(NoteTypeDeletedSchema.to_json())

# convert the object into a dict
note_type_deleted_schema_dict = note_type_deleted_schema_instance.to_dict()
# create an instance of NoteTypeDeletedSchema from a dict
note_type_deleted_schema_from_dict = NoteTypeDeletedSchema.from_dict(note_type_deleted_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


