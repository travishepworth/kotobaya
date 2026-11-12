# MediaPage

A page of media.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[Media]**](Media.md) | Media, newest first (without &#x60;segments&#x60;). | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.media_page import MediaPage

# TODO update the JSON string below
json = "{}"
# create an instance of MediaPage from a JSON string
media_page_instance = MediaPage.from_json(json)
# print the JSON string representation of the object
print(MediaPage.to_json())

# convert the object into a dict
media_page_dict = media_page_instance.to_dict()
# create an instance of MediaPage from a dict
media_page_from_dict = MediaPage.from_dict(media_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


