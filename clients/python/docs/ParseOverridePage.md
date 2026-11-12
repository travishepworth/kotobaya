# ParseOverridePage

A page of parse overrides.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[ParseOverride]**](ParseOverride.md) | Overrides, most recently changed first. | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.parse_override_page import ParseOverridePage

# TODO update the JSON string below
json = "{}"
# create an instance of ParseOverridePage from a JSON string
parse_override_page_instance = ParseOverridePage.from_json(json)
# print the JSON string representation of the object
print(ParseOverridePage.to_json())

# convert the object into a dict
parse_override_page_dict = parse_override_page_instance.to_dict()
# create an instance of ParseOverridePage from a dict
parse_override_page_from_dict = ParseOverridePage.from_dict(parse_override_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


