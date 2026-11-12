# NoteTypeCreate

New note type.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**base_type** | [**NoteBaseType**](NoteBaseType.md) |  | 
**css** | **str** | Extra CSS. | [optional] [default to '']
**fields** | [**List[NoteField]**](NoteField.md) | Ordered field definitions; keys unique. | 
**id** | **str** | Optional client-generated UUIDv7 (idempotent creation). | [optional] 
**name** | **str** | Display name. | 
**templates** | [**Dict[str, CardTemplate]**](CardTemplate.md) | Card kinds keyed by kind. | 

## Example

```python
from kotobaya_client.models.note_type_create import NoteTypeCreate

# TODO update the JSON string below
json = "{}"
# create an instance of NoteTypeCreate from a JSON string
note_type_create_instance = NoteTypeCreate.from_json(json)
# print the JSON string representation of the object
print(NoteTypeCreate.to_json())

# convert the object into a dict
note_type_create_dict = note_type_create_instance.to_dict()
# create an instance of NoteTypeCreate from a dict
note_type_create_from_dict = NoteTypeCreate.from_dict(note_type_create_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


