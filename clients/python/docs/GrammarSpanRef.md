# GrammarSpanRef

A grammar span inside a sentence.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**end_offset** | **int** | UTF-16 offset, exclusive. | 
**grammar_point_id** | **str** | The grammar point. | 
**origin** | [**SpanOrigin**](SpanOrigin.md) |  | 
**start_offset** | **int** | UTF-16 offset, inclusive. | 

## Example

```python
from kotobaya_client.models.grammar_span_ref import GrammarSpanRef

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarSpanRef from a JSON string
grammar_span_ref_instance = GrammarSpanRef.from_json(json)
# print the JSON string representation of the object
print(GrammarSpanRef.to_json())

# convert the object into a dict
grammar_span_ref_dict = grammar_span_ref_instance.to_dict()
# create an instance of GrammarSpanRef from a dict
grammar_span_ref_from_dict = GrammarSpanRef.from_dict(grammar_span_ref_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


