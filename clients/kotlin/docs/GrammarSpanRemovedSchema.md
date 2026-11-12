
# GrammarspanRemovedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **endOffset** | **kotlin.Int** | Exclusive end; must be &gt; start_offset (server rejects otherwise). entity_id &#x3D; &#39;&lt;sentence_id&gt;|&lt;grammar_point_id&gt;|&lt;start_offset&gt;|&lt;end_offset&gt;|manual&#39;. |  |
| **grammarPointId** | **kotlin.String** | Grammar point; must exist (may be tombstoned). |  |
| **sentenceId** | **kotlin.String** | Soft reference to sentence.id; never validated. |  |
| **startOffset** | **kotlin.Int** | UTF-16 code-unit offset into sentence.text, inclusive. |  |



