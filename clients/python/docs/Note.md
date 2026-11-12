# Note

A note (`app.note`) with its live cards.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**cards** | [**List[Card]**](Card.md) | Live cards of the note, ordered by kind. Their &#x60;note&#x60; property is omitted. | 
**created_at** | **datetime** | Creation time. | 
**created_by_device_id** | **str** | Origin device of the creating op. | 
**deleted_at** | **datetime** | Tombstone. | 
**entry_seq** | **int** | Dictionary entry (vocab notes); null when unmatched. | 
**fields** | **Dict[str, str]** | Field values by key: HTML fragments; audio as &#x60;[sound:&lt;sha256&gt;]&#x60;, images as &#x60;&lt;img src&#x3D;\&quot;blob:&lt;sha256&gt;\&quot;&gt;&#x60;. For referenced notes an empty or missing field means \&quot;render from the dictionary\&quot;. | 
**grammar_point_id** | **str** | Grammar point (grammar notes). | 
**id** | **str** | Note id. | 
**kanji_literal** | **str** | Kanji (kanji notes). | 
**language** | **str** | BCP 47; always &#x60;ja&#x60; in v1. | 
**merged_into_id** | **str** | Set on a merge loser - the survivor&#39;s id. Only visible with &#x60;include_deleted&#x3D;true&#x60; in lists. | 
**mnemonic** | **str** | Mnemonic (Markdown); empty if none. | 
**note_type_id** | **str** | Note type. Immutable. | 
**origin** | **str** | How the note was created: &#x60;manual&#x60;, &#x60;takoboto&#x60;, &#x60;yomitan&#x60;, &#x60;ankiconnect&#x60;, &#x60;share&#x60;, &#x60;reader&#x60;, &#x60;deck&#x60;, &#x60;jpdb_import&#x60;, &#x60;api&#x60;, &#x60;auto_kanji&#x60;, ... (open set). | 
**source_media_id** | **str** | Soft reference to the media; may dangle. | 
**source_sentence** | **str** | Sentence the note was mined from, by value; empty if none. | 
**source_sentence_id** | **str** | Soft reference to the sentence; may dangle. | 
**tags** | **List[str]** | Tags, de-duplicated, sorted ascending. &#x60;leech&#x60; is never stored. | 
**type** | [**NoteBaseType**](NoteBaseType.md) | Equals the note type&#39;s &#x60;base_type&#x60;. | 
**updated_at** | **datetime** | Last change. | 

## Example

```python
from kotobaya_client.models.note import Note

# TODO update the JSON string below
json = "{}"
# create an instance of Note from a JSON string
note_instance = Note.from_json(json)
# print the JSON string representation of the object
print(Note.to_json())

# convert the object into a dict
note_dict = note_instance.to_dict()
# create an instance of Note from a dict
note_from_dict = Note.from_dict(note_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


