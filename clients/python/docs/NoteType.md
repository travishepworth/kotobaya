# NoteType

A note type (`app.note_type`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**base_type** | [**NoteBaseType**](NoteBaseType.md) |  | 
**builtin** | **bool** | Built-ins cannot be deleted and keep their field keys. | 
**created_at** | **datetime** | Creation time (epoch for built-ins). | 
**css** | **str** | Extra CSS appended after the shared card CSS. | 
**deleted_at** | **datetime** | Tombstone. | 
**fields** | [**List[NoteField]**](NoteField.md) | Ordered field definitions. | 
**id** | **str** | Note type id. Built-ins are &#x60;00000000-0000-7000-8000-000000000101&#x60; to &#x60;...106&#x60;. | 
**name** | **str** | Display name. | 
**templates** | [**Dict[str, CardTemplate]**](CardTemplate.md) | Card kinds, keyed by kind (&#x60;^[a-z][a-z0-9_]*$&#x60;). | 
**updated_at** | **datetime** | Last change. | 

## Example

```python
from kotobaya_client.models.note_type import NoteType

# TODO update the JSON string below
json = "{}"
# create an instance of NoteType from a JSON string
note_type_instance = NoteType.from_json(json)
# print the JSON string representation of the object
print(NoteType.to_json())

# convert the object into a dict
note_type_dict = note_type_instance.to_dict()
# create an instance of NoteType from a dict
note_type_from_dict = NoteType.from_dict(note_type_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


