# GrammarSpanPutSchema

Adds (or re-adds) a manual grammar highlight. Whole-row last-writer-wins on hlc together with grammar.span_removed. OPS.md#grammarspan_put

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**end_offset** | **int** | Exclusive end; must be &gt; start_offset (server rejects otherwise). entity_id &#x3D; &#39;&lt;sentence_id&gt;|&lt;grammar_point_id&gt;|&lt;start_offset&gt;|&lt;end_offset&gt;|manual&#39;. | 
**grammar_point_id** | **str** | Grammar point; must exist (may be tombstoned). | 
**media_id** | **str** | Optional hint: the sentence&#39;s media. Default null. | [optional] 
**sentence_id** | **str** | Soft reference to sentence.id; never validated. | 
**start_offset** | **int** | UTF-16 code-unit offset into sentence.text, inclusive. | 

## Example

```python
from kotobaya_client.models.grammar_span_put_schema import GrammarSpanPutSchema

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarSpanPutSchema from a JSON string
grammar_span_put_schema_instance = GrammarSpanPutSchema.from_json(json)
# print the JSON string representation of the object
print(GrammarSpanPutSchema.to_json())

# convert the object into a dict
grammar_span_put_schema_dict = grammar_span_put_schema_instance.to_dict()
# create an instance of GrammarSpanPutSchema from a dict
grammar_span_put_schema_from_dict = GrammarSpanPutSchema.from_dict(grammar_span_put_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


