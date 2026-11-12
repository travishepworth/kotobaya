# DictEntry

A full JMdict entry (`ref.dict_entry` and its satellites) with the caller's overlay.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**freq_rank** | **int** | Rank in the primary frequency list. | 
**freqs** | [**List[DictEntryFreqsInner]**](DictEntryFreqsInner.md) | Rank per loaded frequency list, ordered by the list&#39;s priority. | 
**headword** | **str** | Display form. | 
**headword_reading** | **str** | First reading. | 
**is_common** | **bool** | JMdict \&quot;common\&quot; marker. | 
**kanji** | **List[str]** | Distinct kanji in the entry&#39;s kanji forms, in order of first appearance. | 
**kanji_forms** | [**List[DictEntryKanjiFormsInner]**](DictEntryKanjiFormsInner.md) | Kanji forms in JMdict order; empty for kana-only entries. | 
**language** | **str** | BCP 47 tag; always &#x60;ja&#x60; in v1. | 
**misc** | **List[str]** | Union of misc tags over all senses. | 
**note_id** | **str** | Your live vocab note for this entry, or null. | 
**pitch** | [**List[DictEntryPitchInner]**](DictEntryPitchInner.md) | Pitch accent per reading (Kanjium); empty when unknown. | 
**pos** | **List[str]** | Union of part-of-speech tags over all senses. | 
**readings** | [**List[DictEntryReadingsInner]**](DictEntryReadingsInner.md) | Readings in JMdict order (at least one). | 
**retired** | **bool** | The entry is no longer in JMdict (still resolvable, excluded from search). | 
**senses** | [**List[DictEntrySensesInner]**](DictEntrySensesInner.md) | Senses in JMdict order. | 
**seq** | **int** | JMdict sequence number (&#x60;ref.dict_entry.seq&#x60;). | 
**status** | [**WordStatusValue**](WordStatusValue.md) | Your effective word status. | 

## Example

```python
from kotobaya_client.models.dict_entry import DictEntry

# TODO update the JSON string below
json = "{}"
# create an instance of DictEntry from a JSON string
dict_entry_instance = DictEntry.from_json(json)
# print the JSON string representation of the object
print(DictEntry.to_json())

# convert the object into a dict
dict_entry_dict = dict_entry_instance.to_dict()
# create an instance of DictEntry from a dict
dict_entry_from_dict = DictEntry.from_dict(dict_entry_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


