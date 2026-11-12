# NoteCreatedSchema

Creates a note. The note's `type` is not carried: every replica copies note_type.base_type. Omitted optional properties take the column default and get no clock. OPS.md#notecreated

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**entry_seq** | **int** | Dictionary entry this vocab note studies; only allowed when the note type&#39;s base_type is vocab. Default null. Register &#39;entry_seq&#39;. | [optional] 
**fields** | **Dict[str, str]** | Field values {field key: HTML fragment}. Each key is its own register &#39;fields.&lt;key&gt;&#39;. Keys need not exist in the note type (unknown keys are kept). | [optional] 
**grammar_point_id** | **str** | Grammar point a grammar note studies; only for base_type grammar; must exist. Immutable. Default null. | [optional] 
**id** | **str** | Id of the new note. Equals the envelope&#39;s entity_id. | 
**kanji_literal** | **str** | The single kanji (one Unicode code point) a kanji note studies; only for base_type kanji. Immutable. Default null. | [optional] 
**language** | **str** | BCP 47 tag; always &#39;ja&#39; in v1. Default &#39;ja&#39;. | [optional] 
**mnemonic** | **str** | User mnemonic (Markdown). Default &#39;&#39;. Register &#39;mnemonic&#39;. | [optional] [default to '']
**note_type_id** | **str** | Note type; must exist (may be tombstoned). Immutable. | 
**origin** | **str** | How the note was created: manual, takoboto, yomitan, ankiconnect, share, reader, deck, jpdb_import, api, auto_kanji. Open set. Immutable. Default &#39;manual&#39;. | [optional] [default to 'manual']
**source_media_id** | **str** | Soft reference to media.id, or null. | [optional] 
**source_sentence** | **str** | Sentence the note was mined from, by value; &#39;&#39; if none. Register &#39;source_sentence&#39; covers this and the two columns below. | [optional] [default to '']
**source_sentence_id** | **str** | Soft reference to sentence.id, or null. | [optional] 
**tags** | **List[str]** | The complete tag set: no duplicates, no whitespace (ECMA-262 \\s, SYNC.md 2.1) and no U+0000 inside a tag, case-sensitive, sorted ascending by Unicode code point by the op&#39;s author. Replicas store it as given. Never contains the virtual tag &#39;leech&#39;. | [optional] 

## Example

```python
from kotobaya_client.models.note_created_schema import NoteCreatedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of NoteCreatedSchema from a JSON string
note_created_schema_instance = NoteCreatedSchema.from_json(json)
# print the JSON string representation of the object
print(NoteCreatedSchema.to_json())

# convert the object into a dict
note_created_schema_dict = note_created_schema_instance.to_dict()
# create an instance of NoteCreatedSchema from a dict
note_created_schema_from_dict = NoteCreatedSchema.from_dict(note_created_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


