# DictHeadword

The label fields of one dictionary entry (`listDictHeadwords`, C-18). A subset of `DictEntrySummary` with identical values; no caller overlay.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**glosses** | **List[str]** | English glosses of the first sense, in order; the first element is the short meaning. Empty when the first sense has no English gloss. | 
**headword** | **str** | First kanji form, or first reading for kana-only entries. | 
**headword_reading** | **str** | First reading. Equal to &#x60;headword&#x60; for kana-only entries. | 
**seq** | **int** | JMdict sequence number (&#x60;ref.dict_entry.seq&#x60;). | 

## Example

```python
from kotobaya_client.models.dict_headword import DictHeadword

# TODO update the JSON string below
json = "{}"
# create an instance of DictHeadword from a JSON string
dict_headword_instance = DictHeadword.from_json(json)
# print the JSON string representation of the object
print(DictHeadword.to_json())

# convert the object into a dict
dict_headword_dict = dict_headword_instance.to_dict()
# create an instance of DictHeadword from a dict
dict_headword_from_dict = DictHeadword.from_dict(dict_headword_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


