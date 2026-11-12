# DictEntrySensesInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**applies_to_kana** | **List[str]** | Readings the sense is restricted to, or &#x60;[\&quot;*\&quot;]&#x60;. | 
**applies_to_kanji** | **List[str]** | Kanji forms the sense is restricted to, or &#x60;[\&quot;*\&quot;]&#x60;. | 
**dialect** | **List[str]** | Dialect tags. | 
**var_field** | **List[str]** | Field tags. | 
**gloss** | [**List[DictEntrySensesInnerGlossInner]**](DictEntrySensesInnerGlossInner.md) | Glosses. | 
**info** | **List[str]** | Sense notes. | 
**misc** | **List[str]** | Misc tags. | 
**pos** | **List[str]** | Part-of-speech tags. | 
**xref** | **List[str]** | Cross-references as JMdict gives them. | 

## Example

```python
from kotobaya_client.models.dict_entry_senses_inner import DictEntrySensesInner

# TODO update the JSON string below
json = "{}"
# create an instance of DictEntrySensesInner from a JSON string
dict_entry_senses_inner_instance = DictEntrySensesInner.from_json(json)
# print the JSON string representation of the object
print(DictEntrySensesInner.to_json())

# convert the object into a dict
dict_entry_senses_inner_dict = dict_entry_senses_inner_instance.to_dict()
# create an instance of DictEntrySensesInner from a dict
dict_entry_senses_inner_from_dict = DictEntrySensesInner.from_dict(dict_entry_senses_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


