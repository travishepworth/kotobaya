# KanjiPartReading

ADR-041. Which reading of a kanji a word uses (`KanjiPart.used_reading`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**kana** | **str** | The part of the word&#39;s reading this kanji takes, in hiragana; it can differ from &#x60;reading&#x60; by gemination, rendaku or absorbed okurigana (学校 がっこう - &#x60;がっ&#x60; for ガク). | 
**reading** | **str** | The stored reading, character for character equal to one element of &#x60;on&#x60; (when &#x60;type&#x60; is &#x60;on&#x60;) or of &#x60;kun&#x60; (when &#x60;kun&#x60;); the element to highlight. | 
**type** | **str** | Whether &#x60;reading&#x60; is an element of the part&#39;s &#x60;on&#x60; or of its &#x60;kun&#x60;. Clients treat an unknown value as \&quot;no used reading\&quot;. | 

## Example

```python
from kotobaya_client.models.kanji_part_reading import KanjiPartReading

# TODO update the JSON string below
json = "{}"
# create an instance of KanjiPartReading from a JSON string
kanji_part_reading_instance = KanjiPartReading.from_json(json)
# print the JSON string representation of the object
print(KanjiPartReading.to_json())

# convert the object into a dict
kanji_part_reading_dict = kanji_part_reading_instance.to_dict()
# create an instance of KanjiPartReading from a dict
kanji_part_reading_from_dict = KanjiPartReading.from_dict(kanji_part_reading_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


