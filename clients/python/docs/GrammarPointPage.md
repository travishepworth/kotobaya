# GrammarPointPage

A page of grammar points.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[GrammarPoint]**](GrammarPoint.md) | Grammar points. | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.grammar_point_page import GrammarPointPage

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarPointPage from a JSON string
grammar_point_page_instance = GrammarPointPage.from_json(json)
# print the JSON string representation of the object
print(GrammarPointPage.to_json())

# convert the object into a dict
grammar_point_page_dict = grammar_point_page_instance.to_dict()
# create an instance of GrammarPointPage from a dict
grammar_point_page_from_dict = GrammarPointPage.from_dict(grammar_point_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


