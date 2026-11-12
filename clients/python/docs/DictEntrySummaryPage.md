# DictEntrySummaryPage

A page of entry summaries.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[DictEntrySummary]**](DictEntrySummary.md) | Entries in result order. | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.dict_entry_summary_page import DictEntrySummaryPage

# TODO update the JSON string below
json = "{}"
# create an instance of DictEntrySummaryPage from a JSON string
dict_entry_summary_page_instance = DictEntrySummaryPage.from_json(json)
# print the JSON string representation of the object
print(DictEntrySummaryPage.to_json())

# convert the object into a dict
dict_entry_summary_page_dict = dict_entry_summary_page_instance.to_dict()
# create an instance of DictEntrySummaryPage from a dict
dict_entry_summary_page_from_dict = DictEntrySummaryPage.from_dict(dict_entry_summary_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


