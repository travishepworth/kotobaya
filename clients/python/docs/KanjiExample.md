# KanjiExample

C-25. One example word of a kanji (`QueueItem.kanji_examples`). `seq`, `headword` and `reading` equal `DictEntrySummary.seq` / `headword` / `headword_reading` of the entry. Fields added later will be optional, so clients must ignore unknown members.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**gloss** | **str** | The English glosses of the entry&#39;s first sense joined with &#x60;\&quot;; \&quot;&#x60; (&#x60;DictEntrySummary.glosses&#x60; joined); the empty string when that sense has no English gloss. | 
**headword** | **str** | The entry&#39;s headword (first kanji form); always contains the kanji. | 
**reading** | **str** | The entry&#39;s first reading (&#x60;headword_reading&#x60;). | 
**reading_type** | **str** | How this word reads the kanji (core &#x60;KanjiExamplePicker.classify&#x60;): &#x60;on&#x60; &#x3D; one of its on&#39;yomi (incl. rendaku and gemination, 食事 しょくじ), &#x60;kun&#x60; &#x3D; one of its kun&#39;yomi (食べる), &#x60;other&#x60; &#x3D; neither could be aligned (jukujikun, ateji, irregular: 今日 きょう). A display hint only. Clients must accept unknown values as &#x60;other&#x60;. | 
**seq** | **int** | JMdict sequence number (&#x60;ref.dict_entry.seq&#x60;). | 

## Example

```python
from kotobaya_client.models.kanji_example import KanjiExample

# TODO update the JSON string below
json = "{}"
# create an instance of KanjiExample from a JSON string
kanji_example_instance = KanjiExample.from_json(json)
# print the JSON string representation of the object
print(KanjiExample.to_json())

# convert the object into a dict
kanji_example_dict = kanji_example_instance.to_dict()
# create an instance of KanjiExample from a dict
kanji_example_from_dict = KanjiExample.from_dict(kanji_example_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


