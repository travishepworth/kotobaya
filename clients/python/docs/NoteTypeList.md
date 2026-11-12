# NoteTypeList

Note types.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[NoteType]**](NoteType.md) | Note types, built-ins first. | 

## Example

```python
from kotobaya_client.models.note_type_list import NoteTypeList

# TODO update the JSON string below
json = "{}"
# create an instance of NoteTypeList from a JSON string
note_type_list_instance = NoteTypeList.from_json(json)
# print the JSON string representation of the object
print(NoteTypeList.to_json())

# convert the object into a dict
note_type_list_dict = note_type_list_instance.to_dict()
# create an instance of NoteTypeList from a dict
note_type_list_from_dict = NoteTypeList.from_dict(note_type_list_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


