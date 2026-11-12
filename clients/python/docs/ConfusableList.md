# ConfusableList

Confusable pairs (unpaginated).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[Confusable]**](Confusable.md) | Pairs. | 

## Example

```python
from kotobaya_client.models.confusable_list import ConfusableList

# TODO update the JSON string below
json = "{}"
# create an instance of ConfusableList from a JSON string
confusable_list_instance = ConfusableList.from_json(json)
# print the JSON string representation of the object
print(ConfusableList.to_json())

# convert the object into a dict
confusable_list_dict = confusable_list_instance.to_dict()
# create an instance of ConfusableList from a dict
confusable_list_from_dict = ConfusableList.from_dict(confusable_list_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


