# GrammarSpanRemovedSchema

Removes a manual grammar highlight (reversible tombstone). OPS.md#grammarspan_removed

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**end_offset** | **int** | Exclusive end; must be &gt; start_offset (server rejects otherwise). entity_id &#x3D; &#39;&lt;sentence_id&gt;|&lt;grammar_point_id&gt;|&lt;start_offset&gt;|&lt;end_offset&gt;|manual&#39;. | 
**grammar_point_id** | **str** | Grammar point; must exist (may be tombstoned). | 
**sentence_id** | **str** | Soft reference to sentence.id; never validated. | 
**start_offset** | **int** | UTF-16 code-unit offset into sentence.text, inclusive. | 

## Example

```python
from kotobaya_client.models.grammar_span_removed_schema import GrammarSpanRemovedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarSpanRemovedSchema from a JSON string
grammar_span_removed_schema_instance = GrammarSpanRemovedSchema.from_json(json)
# print the JSON string representation of the object
print(GrammarSpanRemovedSchema.to_json())

# convert the object into a dict
grammar_span_removed_schema_dict = grammar_span_removed_schema_instance.to_dict()
# create an instance of GrammarSpanRemovedSchema from a dict
grammar_span_removed_schema_from_dict = GrammarSpanRemovedSchema.from_dict(grammar_span_removed_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


