
# Notecreatedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **id** | **kotlin.String** | Id of the new note. Equals the envelope&#39;s entity_id. |  |
| **noteTypeId** | **kotlin.String** | Note type; must exist (may be tombstoned). Immutable. |  |
| **entrySeq** | **kotlin.Int** | Dictionary entry this vocab note studies; only allowed when the note type&#39;s base_type is vocab. Default null. Register &#39;entry_seq&#39;. |  [optional] |
| **fields** | **kotlin.collections.Map&lt;kotlin.String, kotlin.String&gt;** | Field values {field key: HTML fragment}. Each key is its own register &#39;fields.&lt;key&gt;&#39;. Keys need not exist in the note type (unknown keys are kept). |  [optional] |
| **grammarPointId** | **kotlin.String** | Grammar point a grammar note studies; only for base_type grammar; must exist. Immutable. Default null. |  [optional] |
| **kanjiLiteral** | **kotlin.String** | The single kanji (one Unicode code point) a kanji note studies; only for base_type kanji. Immutable. Default null. |  [optional] |
| **language** | [**inline**](#Language) | BCP 47 tag; always &#39;ja&#39; in v1. Default &#39;ja&#39;. |  [optional] |
| **mnemonic** | **kotlin.String** | User mnemonic (Markdown). Default &#39;&#39;. Register &#39;mnemonic&#39;. |  [optional] |
| **origin** | **kotlin.String** | How the note was created: manual, takoboto, yomitan, ankiconnect, share, reader, deck, jpdb_import, api, auto_kanji. Open set. Immutable. Default &#39;manual&#39;. |  [optional] |
| **sourceMediaId** | **kotlin.String** | Soft reference to media.id, or null. |  [optional] |
| **sourceSentence** | **kotlin.String** | Sentence the note was mined from, by value; &#39;&#39; if none. Register &#39;source_sentence&#39; covers this and the two columns below. |  [optional] |
| **sourceSentenceId** | **kotlin.String** | Soft reference to sentence.id, or null. |  [optional] |
| **tags** | **kotlin.collections.Set&lt;kotlin.String&gt;** | The complete tag set: no duplicates, no whitespace (ECMA-262 \\s, SYNC.md 2.1) and no U+0000 inside a tag, case-sensitive, sorted ascending by Unicode code point by the op&#39;s author. Replicas store it as given. Never contains the virtual tag &#39;leech&#39;. |  [optional] |


<a id="Language"></a>
## Enum: language
| Name | Value |
| ---- | ----- |
| language | ja |



