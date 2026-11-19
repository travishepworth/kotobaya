# KanjiWord

A dictionary entry containing the requested kanji, with the decks that hold it (C-17).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**deck_ids** | **List[str]** | C-25 (additive). Ids of your live decks (&#x60;deleted_at&#x60; null; enabled or not) that hold this entry as a live item &#x60;e:&lt;seq&gt;&#x60; (an &#x60;app.deck_item&#x60; row with &#x60;entry_seq&#x60; &#x3D; &#x60;seq&#x60; and &#x60;deleted_at&#x60; null), in draw order: &#x60;deck.priority&#x60; ascending, then deck id ascending (order of the lower-case UUID text). No duplicates; empty when none. Smart decks never appear (they have no items) and &#x60;n:&lt;note id&gt;&#x60; items are not counted (they are on &#x60;Note.deck_ids&#x60;). The same set of decks as &#x60;KanjiWord.decks&#x60;, in another order. Servers that implement C-25 send it wherever this schema is returned. Absent on older servers: clients treat absent as \&quot;unknown\&quot; (fall back to scanning &#x60;listDeckItems&#x60;), never as \&quot;in no deck\&quot;.  | [optional] 
**freq_rank** | **int** | Rank in the primary frequency list, or null. | 
**glosses** | **List[str]** | English glosses of the first sense, in order. | 
**headword** | **str** | Display form. First kanji form; the first reading for kana-only entries and for kana words (ADR-046, every kanji form tagged &#x60;rK&#x60; or &#x60;sK&#x60;, e.g. &#x60;シンガポール&#x60; for 新嘉坡; SCHEMA.md 4.5 \&quot;Headword\&quot;). The other forms stay searchable. | 
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


