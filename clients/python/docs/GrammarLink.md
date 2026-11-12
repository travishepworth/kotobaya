# GrammarLink

A reference link.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**title** | **str** | Link text. | 
**url** | **str** | Target. | 

## Example

```python
from kotobaya_client.models.grammar_link import GrammarLink

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarLink from a JSON string
grammar_link_instance = GrammarLink.from_json(json)
# print the JSON string representation of the object
print(GrammarLink.to_json())

# convert the object into a dict
grammar_link_dict = grammar_link_instance.to_dict()
# create an instance of GrammarLink from a dict
grammar_link_from_dict = GrammarLink.from_dict(grammar_link_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


