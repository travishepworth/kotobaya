# JpdbVocabPage

A page of jpdb vocabulary.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[JpdbVocab]**](JpdbVocab.md) | Rows, by &#x60;occurrence_total&#x60; descending then &#x60;jpdb_vid&#x60;. | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.jpdb_vocab_page import JpdbVocabPage

# TODO update the JSON string below
json = "{}"
# create an instance of JpdbVocabPage from a JSON string
jpdb_vocab_page_instance = JpdbVocabPage.from_json(json)
# print the JSON string representation of the object
print(JpdbVocabPage.to_json())

# convert the object into a dict
jpdb_vocab_page_dict = jpdb_vocab_page_instance.to_dict()
# create an instance of JpdbVocabPage from a dict
jpdb_vocab_page_from_dict = JpdbVocabPage.from_dict(jpdb_vocab_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


