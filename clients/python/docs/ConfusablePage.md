# ConfusablePage

A page of confusable pairs.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[Confusable]**](Confusable.md) | Pairs, most recently changed first. | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.confusable_page import ConfusablePage

# TODO update the JSON string below
json = "{}"
# create an instance of ConfusablePage from a JSON string
confusable_page_instance = ConfusablePage.from_json(json)
# print the JSON string representation of the object
print(ConfusablePage.to_json())

# convert the object into a dict
confusable_page_dict = confusable_page_instance.to_dict()
# create an instance of ConfusablePage from a dict
confusable_page_from_dict = ConfusablePage.from_dict(confusable_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


