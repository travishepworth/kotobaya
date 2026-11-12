# NoteTypeUpdatedSchema

Sets a subset of a note type's registers, all at the op's hlc. OPS.md#note_typeupdated

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the note type. Equals the envelope&#39;s entity_id. | 
**set** | [**NoteTypeUpdatedSchemaSet**](NoteTypeUpdatedSchemaSet.md) |  | 

## Example

```python
from kotobaya_client.models.note_type_updated_schema import NoteTypeUpdatedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of NoteTypeUpdatedSchema from a JSON string
note_type_updated_schema_instance = NoteTypeUpdatedSchema.from_json(json)
# print the JSON string representation of the object
print(NoteTypeUpdatedSchema.to_json())

# convert the object into a dict
note_type_updated_schema_dict = note_type_updated_schema_instance.to_dict()
# create an instance of NoteTypeUpdatedSchema from a dict
note_type_updated_schema_from_dict = NoteTypeUpdatedSchema.from_dict(note_type_updated_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


