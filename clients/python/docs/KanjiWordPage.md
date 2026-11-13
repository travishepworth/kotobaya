# KanjiWordPage

A page of entries containing a kanji (`listKanjiWords`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[KanjiWord]**](KanjiWord.md) | Entries in result order (see &#x60;listKanjiWords&#x60;). | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.kanji_word_page import KanjiWordPage

# TODO update the JSON string below
json = "{}"
# create an instance of KanjiWordPage from a JSON string
kanji_word_page_instance = KanjiWordPage.from_json(json)
# print the JSON string representation of the object
print(KanjiWordPage.to_json())

# convert the object into a dict
kanji_word_page_dict = kanji_word_page_instance.to_dict()
# create an instance of KanjiWordPage from a dict
kanji_word_page_from_dict = KanjiWordPage.from_dict(kanji_word_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


