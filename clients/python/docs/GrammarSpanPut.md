# GrammarSpanPut

Optional hint for a manual span.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**media_id** | **str** | Media of the sentence; used only when the server does not have the sentence. | [optional] 

## Example

```python
from kotobaya_client.models.grammar_span_put import GrammarSpanPut

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarSpanPut from a JSON string
grammar_span_put_instance = GrammarSpanPut.from_json(json)
# print the JSON string representation of the object
print(GrammarSpanPut.to_json())

# convert the object into a dict
grammar_span_put_dict = grammar_span_put_instance.to_dict()
# create an instance of GrammarSpanPut from a dict
grammar_span_put_from_dict = GrammarSpanPut.from_dict(grammar_span_put_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


