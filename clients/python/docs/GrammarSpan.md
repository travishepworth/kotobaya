# GrammarSpan

A grammar span (`app.sentence_grammar_span`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**created_at** | **datetime** | Creation time. | 
**end_offset** | **int** | UTF-16 offset, exclusive. | 
**grammar_point_id** | **str** | Grammar point. | 
**media_id** | **str** | Media of the sentence, when known. | 
**origin** | [**SpanOrigin**](SpanOrigin.md) |  | 
**rule_index** | **int** | Detected spans - index of the matching rule; null for manual. | 
**sentence_id** | **str** | Sentence (soft reference). | 
**start_offset** | **int** | UTF-16 offset, inclusive. | 

## Example

```python
from kotobaya_client.models.grammar_span import GrammarSpan

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarSpan from a JSON string
grammar_span_instance = GrammarSpan.from_json(json)
# print the JSON string representation of the object
print(GrammarSpan.to_json())

# convert the object into a dict
grammar_span_dict = grammar_span_instance.to_dict()
# create an instance of GrammarSpan from a dict
grammar_span_from_dict = GrammarSpan.from_dict(grammar_span_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


