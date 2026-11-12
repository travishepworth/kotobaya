# DictEntryKanjiFormsInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**common** | **bool** | Common marker. | 
**tags** | **List[str]** | JMdict ke_inf tags. | 
**text** | **str** | The form. | 

## Example

```python
from kotobaya_client.models.dict_entry_kanji_forms_inner import DictEntryKanjiFormsInner

# TODO update the JSON string below
json = "{}"
# create an instance of DictEntryKanjiFormsInner from a JSON string
dict_entry_kanji_forms_inner_instance = DictEntryKanjiFormsInner.from_json(json)
# print the JSON string representation of the object
print(DictEntryKanjiFormsInner.to_json())

# convert the object into a dict
dict_entry_kanji_forms_inner_dict = dict_entry_kanji_forms_inner_instance.to_dict()
# create an instance of DictEntryKanjiFormsInner from a dict
dict_entry_kanji_forms_inner_from_dict = DictEntryKanjiFormsInner.from_dict(dict_entry_kanji_forms_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


