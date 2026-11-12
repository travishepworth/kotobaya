# NoteTypeCreatedSchemaFieldsInner

One field. Unknown keys are preserved.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**key** | **str** | Field key used in note.fields and {{placeholders}}. | 
**kind** | **str** | What the field holds. | 
**label** | **str** | Display label. | 

## Example

```python
from kotobaya_client.models.note_type_created_schema_fields_inner import NoteTypeCreatedSchemaFieldsInner

# TODO update the JSON string below
json = "{}"
# create an instance of NoteTypeCreatedSchemaFieldsInner from a JSON string
note_type_created_schema_fields_inner_instance = NoteTypeCreatedSchemaFieldsInner.from_json(json)
# print the JSON string representation of the object
print(NoteTypeCreatedSchemaFieldsInner.to_json())

# convert the object into a dict
note_type_created_schema_fields_inner_dict = note_type_created_schema_fields_inner_instance.to_dict()
# create an instance of NoteTypeCreatedSchemaFieldsInner from a dict
note_type_created_schema_fields_inner_from_dict = NoteTypeCreatedSchemaFieldsInner.from_dict(note_type_created_schema_fields_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


