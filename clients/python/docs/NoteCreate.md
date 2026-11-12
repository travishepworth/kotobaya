# NoteCreate

New note.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**card_kinds** | **List[str]** | Card kinds to create. Default - the note type&#39;s default kinds. | [optional] 
**deck_id** | **str** | Also add the note (or its entry) to this user deck. | [optional] 
**entry_seq** | **int** | Dictionary entry; vocab types only. | [optional] 
**fields** | **Dict[str, str]** | Field values by key. | [optional] 
**grammar_point_id** | **str** | A live grammar point; grammar types only. | [optional] 
**id** | **str** | Optional client-generated UUIDv7 (idempotent creation). | [optional] 
**kanji_literal** | **str** | One kanji (one code point); kanji types only. | [optional] 
**mnemonic** | **str** | Mnemonic (Markdown). | [optional] [default to '']
**note_type_id** | **str** | A live note type. | 
**origin** | **str** | How the note was created. | [optional] [default to 'api']
**source_media_id** | **str** | Soft reference to the media. | [optional] 
**source_sentence** | **str** | Sentence the note was mined from. | [optional] [default to '']
**source_sentence_id** | **str** | Soft reference to the sentence. | [optional] 
**tags** | **List[str]** | Tags. | [optional] [default to []]

## Example

```python
from kotobaya_client.models.note_create import NoteCreate

# TODO update the JSON string below
json = "{}"
# create an instance of NoteCreate from a JSON string
note_create_instance = NoteCreate.from_json(json)
# print the JSON string representation of the object
print(NoteCreate.to_json())

# convert the object into a dict
note_create_dict = note_create_instance.to_dict()
# create an instance of NoteCreate from a dict
note_create_from_dict = NoteCreate.from_dict(note_create_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


