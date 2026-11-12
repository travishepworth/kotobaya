# NoteTypeUpdatedSchemaSet

Registers to write. At least one.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**css** | **str** | Register &#39;css&#39;. | [optional] 
**fields** | [**List[NoteTypeCreatedSchemaFieldsInner]**](NoteTypeCreatedSchemaFieldsInner.md) | Ordered field definitions; the whole array is ONE register (&#39;fields&#39;). Keys must be unique within the array (server rejects duplicates with invalid_value). Keys are never renamed. For a vocab note type the keys MUST include expression, reading and meaning (invalid_value otherwise; OPS.md, C-2). | [optional] 
**name** | **str** | Register &#39;name&#39;. | [optional] 
**templates** | [**Dict[str, Optional[Template]]**](Template.md) | One register &#39;templates.&lt;kind&gt;&#39; per key present. null removes the kind from the object (the register keeps its clock). | [optional] 

## Example

```python
from kotobaya_client.models.note_type_updated_schema_set import NoteTypeUpdatedSchemaSet

# TODO update the JSON string below
json = "{}"
# create an instance of NoteTypeUpdatedSchemaSet from a JSON string
note_type_updated_schema_set_instance = NoteTypeUpdatedSchemaSet.from_json(json)
# print the JSON string representation of the object
print(NoteTypeUpdatedSchemaSet.to_json())

# convert the object into a dict
note_type_updated_schema_set_dict = note_type_updated_schema_set_instance.to_dict()
# create an instance of NoteTypeUpdatedSchemaSet from a dict
note_type_updated_schema_set_from_dict = NoteTypeUpdatedSchemaSet.from_dict(note_type_updated_schema_set_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


