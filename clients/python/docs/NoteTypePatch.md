# NoteTypePatch

Changes to a note type. At least one property.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**css** | **str** | Extra CSS. | [optional] 
**fields** | [**List[NoteField]**](NoteField.md) | The whole new field list. | [optional] 
**name** | **str** | Display name. | [optional] 
**templates** | [**Dict[str, CardTemplate]**](CardTemplate.md) | Card kinds to add or replace (partial map). | [optional] 

## Example

```python
from kotobaya_client.models.note_type_patch import NoteTypePatch

# TODO update the JSON string below
json = "{}"
# create an instance of NoteTypePatch from a JSON string
note_type_patch_instance = NoteTypePatch.from_json(json)
# print the JSON string representation of the object
print(NoteTypePatch.to_json())

# convert the object into a dict
note_type_patch_dict = note_type_patch_instance.to_dict()
# create an instance of NoteTypePatch from a dict
note_type_patch_from_dict = NoteTypePatch.from_dict(note_type_patch_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


