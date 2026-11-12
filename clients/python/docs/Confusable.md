# Confusable

A confusable pair (`app.confusable`), with merge losers resolved to survivors.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**created_at** | **datetime** | When the pair was first suggested or recorded. | 
**note_a** | [**NoteSummary**](NoteSummary.md) |  | 
**note_a_id** | **str** | The smaller note id of the pair. | 
**note_b** | [**NoteSummary**](NoteSummary.md) |  | 
**note_b_id** | **str** | The larger note id. | 
**reason** | [**ConfusableReason**](ConfusableReason.md) |  | 
**status** | [**ConfusableStatus**](ConfusableStatus.md) |  | 
**updated_at** | **datetime** | Last status change. | 

## Example

```python
from kotobaya_client.models.confusable import Confusable

# TODO update the JSON string below
json = "{}"
# create an instance of Confusable from a JSON string
confusable_instance = Confusable.from_json(json)
# print the JSON string representation of the object
print(Confusable.to_json())

# convert the object into a dict
confusable_dict = confusable_instance.to_dict()
# create an instance of Confusable from a dict
confusable_from_dict = Confusable.from_dict(confusable_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


