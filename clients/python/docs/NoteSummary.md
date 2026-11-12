# NoteSummary

The parts of a note needed to render and identify a card.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**entry_seq** | **int** | Referenced entry (vocab only). | 
**fields** | **Dict[str, str]** | Field values (HTML fragments) by field key. | 
**grammar_point_id** | **str** | Referenced grammar point (grammar notes only). | 
**id** | **str** | Note id. | 
**kanji_literal** | **str** | Referenced kanji (kanji notes only). | 
**mnemonic** | **str** | Mnemonic (Markdown); empty if none. | 
**note_type_id** | **str** | Note type. | 
**source_sentence** | **str** | Sentence the note was mined from; empty if none. | 
**tags** | **List[str]** | Tags, sorted ascending. | 
**type** | [**NoteBaseType**](NoteBaseType.md) |  | 

## Example

```python
from kotobaya_client.models.note_summary import NoteSummary

# TODO update the JSON string below
json = "{}"
# create an instance of NoteSummary from a JSON string
note_summary_instance = NoteSummary.from_json(json)
# print the JSON string representation of the object
print(NoteSummary.to_json())

# convert the object into a dict
note_summary_dict = note_summary_instance.to_dict()
# create an instance of NoteSummary from a dict
note_summary_from_dict = NoteSummary.from_dict(note_summary_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


