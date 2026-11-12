# PushResult

Outcome of one pushed op.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**error** | [**PushResultError**](PushResultError.md) |  | [optional] 
**op_id** | **str** | The op this result is for. | 
**seq** | **int** | change.seq of the op. Present iff status is accepted or duplicate. | [optional] 
**status** | **str** | accepted: applied and logged now. duplicate: this op_id was already in the log; nothing was done. rejected: permanently refused; nothing was done. | 

## Example

```python
from kotobaya_client.models.push_result import PushResult

# TODO update the JSON string below
json = "{}"
# create an instance of PushResult from a JSON string
push_result_instance = PushResult.from_json(json)
# print the JSON string representation of the object
print(PushResult.to_json())

# convert the object into a dict
push_result_dict = push_result_instance.to_dict()
# create an instance of PushResult from a dict
push_result_from_dict = PushResult.from_dict(push_result_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


