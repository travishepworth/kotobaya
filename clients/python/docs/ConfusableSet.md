# ConfusableSet

Decision on a pair.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**status** | **str** | The decision. | 

## Example

```python
from kotobaya_client.models.confusable_set import ConfusableSet

# TODO update the JSON string below
json = "{}"
# create an instance of ConfusableSet from a JSON string
confusable_set_instance = ConfusableSet.from_json(json)
# print the JSON string representation of the object
print(ConfusableSet.to_json())

# convert the object into a dict
confusable_set_dict = confusable_set_instance.to_dict()
# create an instance of ConfusableSet from a dict
confusable_set_from_dict = ConfusableSet.from_dict(confusable_set_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


