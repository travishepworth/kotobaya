# NoteUpdatedSchema

Sets a subset of a note's registers, all at the op's hlc. OPS.md#noteupdated

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the note. Equals the envelope&#39;s entity_id. | 
**set** | [**NoteUpdatedSchemaSet**](NoteUpdatedSchemaSet.md) |  | 

## Example

```python
from kotobaya_client.models.note_updated_schema import NoteUpdatedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of NoteUpdatedSchema from a JSON string
note_updated_schema_instance = NoteUpdatedSchema.from_json(json)
# print the JSON string representation of the object
print(NoteUpdatedSchema.to_json())

# convert the object into a dict
note_updated_schema_dict = note_updated_schema_instance.to_dict()
# create an instance of NoteUpdatedSchema from a dict
note_updated_schema_from_dict = NoteUpdatedSchema.from_dict(note_updated_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


