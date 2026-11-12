# Kanji

A kanji (`ref.kanji`) with the caller's overlay.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**components** | **List[str]** | KRADFILE components. | 
**freq_rank** | **int** | Newspaper frequency rank. | 
**grade** | **int** | KANJIDIC2 grade. | 
**jlpt** | **int** | JLPT N-level. | 
**kun_readings** | **List[str]** | Kun readings with okurigana dots. | 
**literal** | **str** | The kanji. | 
**meanings** | **List[str]** | English meanings. | 
**nanori** | **List[str]** | Name readings. | 
**note_id** | **str** | Your live kanji note, or null. | 
**on_readings** | **List[str]** | On readings (katakana). | 
**radical** | **str** | Classical radical. | 
**stroke_count** | **int** | Stroke count. | 

## Example

```python
from kotobaya_client.models.kanji import Kanji

# TODO update the JSON string below
json = "{}"
# create an instance of Kanji from a JSON string
kanji_instance = Kanji.from_json(json)
# print the JSON string representation of the object
print(Kanji.to_json())

# convert the object into a dict
kanji_dict = kanji_instance.to_dict()
# create an instance of Kanji from a dict
kanji_from_dict = Kanji.from_dict(kanji_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


