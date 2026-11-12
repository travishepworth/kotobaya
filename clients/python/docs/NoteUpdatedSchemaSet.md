# NoteUpdatedSchemaSet

Registers to write. At least one. The three source_* properties form one register and must be given together.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**entry_seq** | **int** | Register &#39;entry_seq&#39;: match, re-match or un-match (null) a vocab note. May trigger a merge on the server. | [optional] 
**fields** | **Dict[str, str]** | Field values {field key: HTML fragment}. Each key is its own register &#39;fields.&lt;key&gt;&#39;. Keys need not exist in the note type (unknown keys are kept). | [optional] 
**mnemonic** | **str** | Register &#39;mnemonic&#39;. | [optional] 
**source_media_id** | **str** | Soft reference to media.id, or null. | [optional] 
**source_sentence** | **str** | Sentence the note was mined from, by value; &#39;&#39; if none. Register &#39;source_sentence&#39; covers this and the two columns below. | [optional] 
**source_sentence_id** | **str** | Soft reference to sentence.id, or null. | [optional] 
**tags** | **List[str]** | The complete tag set: no duplicates, no whitespace (ECMA-262 \\s, SYNC.md 2.1) and no U+0000 inside a tag, case-sensitive, sorted ascending by Unicode code point by the op&#39;s author. Replicas store it as given. Never contains the virtual tag &#39;leech&#39;. | [optional] 

## Example

```python
from kotobaya_client.models.note_updated_schema_set import NoteUpdatedSchemaSet

# TODO update the JSON string below
json = "{}"
# create an instance of NoteUpdatedSchemaSet from a JSON string
note_updated_schema_set_instance = NoteUpdatedSchemaSet.from_json(json)
# print the JSON string representation of the object
print(NoteUpdatedSchemaSet.to_json())

# convert the object into a dict
note_updated_schema_set_dict = note_updated_schema_set_instance.to_dict()
# create an instance of NoteUpdatedSchemaSet from a dict
note_updated_schema_set_from_dict = NoteUpdatedSchemaSet.from_dict(note_updated_schema_set_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


