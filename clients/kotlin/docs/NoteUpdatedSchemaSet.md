
# NoteUpdatedSchemaSet

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **entrySeq** | **kotlin.Int** | Register &#39;entry_seq&#39;: match, re-match or un-match (null) a vocab note. May trigger a merge on the server. |  [optional] |
| **fields** | **kotlin.collections.Map&lt;kotlin.String, kotlin.String?&gt;** | Field values {field key: HTML fragment}. Each key is its own register &#39;fields.&lt;key&gt;&#39;. Keys need not exist in the note type (unknown keys are kept). |  [optional] |
| **mnemonic** | **kotlin.String** | Register &#39;mnemonic&#39;. |  [optional] |
| **sourceMediaId** | **kotlin.String** | Soft reference to media.id, or null. |  [optional] |
| **sourceSentence** | **kotlin.String** | Sentence the note was mined from, by value; &#39;&#39; if none. Register &#39;source_sentence&#39; covers this and the two columns below. |  [optional] |
| **sourceSentenceId** | **kotlin.String** | Soft reference to sentence.id, or null. |  [optional] |
| **tags** | **kotlin.collections.Set&lt;kotlin.String&gt;** | The complete tag set: no duplicates, no whitespace inside a tag, case-sensitive, sorted ascending by Unicode code point by the op&#39;s author. Replicas store it as given. Never contains the virtual tag &#39;leech&#39;. |  [optional] |



