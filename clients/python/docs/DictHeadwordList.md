# DictHeadwordList

Headwords for a batch of entries (`listDictHeadwords`, C-18). Not paginated.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[DictHeadword]**](DictHeadword.md) | One item per distinct requested &#x60;seq&#x60; that exists, by &#x60;seq&#x60; ascending. Unknown &#x60;seq&#x60; values are omitted. | 

## Example

```python
from kotobaya_client.models.dict_headword_list import DictHeadwordList

# TODO update the JSON string below
json = "{}"
# create an instance of DictHeadwordList from a JSON string
dict_headword_list_instance = DictHeadwordList.from_json(json)
# print the JSON string representation of the object
print(DictHeadwordList.to_json())

# convert the object into a dict
dict_headword_list_dict = dict_headword_list_instance.to_dict()
# create an instance of DictHeadwordList from a dict
dict_headword_list_from_dict = DictHeadwordList.from_dict(dict_headword_list_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


