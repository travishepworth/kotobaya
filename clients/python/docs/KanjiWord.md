# KanjiWord

A dictionary entry containing the requested kanji, with the decks that hold it (C-17).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**freq_rank** | **int** | Rank in the primary frequency list, or null. | 
**glosses** | **List[str]** | English glosses of the first sense, in order. | 
**headword** | **str** | First kanji form, or first reading for kana-only entries. | 
**headword_reading** | **str** | First reading. | 
**is_common** | **bool** | JMdict \&quot;common\&quot; marker. | 
**note_id** | **str** | Your live vocab note for this entry, or null. | 
**pos** | **List[str]** | Union of JMdict part-of-speech tags. | 
**retired** | **bool** | The entry is no longer in JMdict. | 
**seq** | **int** | JMdict sequence number (&#x60;ref.dict_entry.seq&#x60;). | 
**status** | [**WordStatusValue**](WordStatusValue.md) | Your effective word status (&#x60;new&#x60; when there is no row). | 
**decks** | [**List[KanjiWordDeck]**](KanjiWordDeck.md) | Your live decks holding this entry as a live item, by &#x60;name&#x60; (code-point order) then &#x60;deck_id&#x60;. Empty when none. | 

## Example

```python
from kotobaya_client.models.kanji_word import KanjiWord

# TODO update the JSON string below
json = "{}"
# create an instance of KanjiWord from a JSON string
kanji_word_instance = KanjiWord.from_json(json)
# print the JSON string representation of the object
print(KanjiWord.to_json())

# convert the object into a dict
kanji_word_dict = kanji_word_instance.to_dict()
# create an instance of KanjiWord from a dict
kanji_word_from_dict = KanjiWord.from_dict(kanji_word_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


