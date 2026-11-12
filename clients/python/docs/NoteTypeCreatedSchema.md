# NoteTypeCreatedSchema

Creates a user-defined note type. Built-ins are seeded, never created by ops. OPS.md#note_typecreated

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**base_type** | **str** | Which of the six note types this is. Immutable. | 
**css** | **str** | Extra CSS appended after assets/card/ CSS. Default &#39;&#39;. | [optional] [default to '']
**fields** | [**List[NoteTypeCreatedSchemaFieldsInner]**](NoteTypeCreatedSchemaFieldsInner.md) | Ordered field definitions; the whole array is ONE register (&#39;fields&#39;). Keys must be unique within the array (server rejects duplicates with invalid_value). Keys are never renamed. For base_type vocab the keys MUST include expression, reading and meaning (server rejects otherwise with invalid_value; OPS.md, C-2). | 
**id** | **str** | Id of the new note type. Equals the envelope&#39;s entity_id. | 
**name** | **str** | Display name. | 
**templates** | [**Dict[str, Template]**](Template.md) | Card kinds: {kind: template}. Each kind is its own register &#39;templates.&lt;kind&gt;&#39;. | 

## Example

```python
from kotobaya_client.models.note_type_created_schema import NoteTypeCreatedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of NoteTypeCreatedSchema from a JSON string
note_type_created_schema_instance = NoteTypeCreatedSchema.from_json(json)
# print the JSON string representation of the object
print(NoteTypeCreatedSchema.to_json())

# convert the object into a dict
note_type_created_schema_dict = note_type_created_schema_instance.to_dict()
# create an instance of NoteTypeCreatedSchema from a dict
note_type_created_schema_from_dict = NoteTypeCreatedSchema.from_dict(note_type_created_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


