# KanjiPart

ADR-041. One kanji of a vocab word (`QueueItem.kanji_parts`); the wire form of the core's `KanjiPart`. Fields added later will be optional, so clients must ignore unknown members.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**keyword** | **str** | Your keyword for the kanji - the plain text of the &#x60;keyword&#x60; field of your live kanji note for this literal when it is not blank - else the first of &#x60;meanings&#x60;; null when there is neither. | 
**kun** | **List[str]** | Kun readings as stored (&#x60;Kanji.kun_readings&#x60;, hiragana with okurigana dots and &#x60;-&#x60;). May be empty. | 
**literal** | **str** | The kanji, exactly one Unicode code point (two UTF-16 units outside the BMP). | 
**meanings** | **List[str]** | English meanings (&#x60;Kanji.meanings&#x60;, KANJIDIC2 order). Empty when the kanji is not in the dictionary. | 
**on** | **List[str]** | On readings as stored (&#x60;Kanji.on_readings&#x60;, katakana). May be empty. | 
**used_reading** | [**KanjiPartReading**](KanjiPartReading.md) | The reading this word uses for the kanji (core &#x60;KanjiParts.usedReading&#x60;), or null when the word&#39;s reading cannot be aligned to one of the kanji&#39;s readings (jukujikun and ateji such as 今日 きょう, irregular readings such as 日本 にほん, a kanji without readings, an empty reading). | 

## Example

```python
from kotobaya_client.models.kanji_part import KanjiPart

# TODO update the JSON string below
json = "{}"
# create an instance of KanjiPart from a JSON string
kanji_part_instance = KanjiPart.from_json(json)
# print the JSON string representation of the object
print(KanjiPart.to_json())

# convert the object into a dict
kanji_part_dict = kanji_part_instance.to_dict()
# create an instance of KanjiPart from a dict
kanji_part_from_dict = KanjiPart.from_dict(kanji_part_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


