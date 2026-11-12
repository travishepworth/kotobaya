# NotePage

A page of notes.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[Note]**](Note.md) | Notes, newest first. | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.note_page import NotePage

# TODO update the JSON string below
json = "{}"
# create an instance of NotePage from a JSON string
note_page_instance = NotePage.from_json(json)
# print the JSON string representation of the object
print(NotePage.to_json())

# convert the object into a dict
note_page_dict = note_page_instance.to_dict()
# create an instance of NotePage from a dict
note_page_from_dict = NotePage.from_dict(note_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


