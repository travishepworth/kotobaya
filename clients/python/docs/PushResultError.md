# PushResultError

Present iff status = rejected.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**code** | **str** | Machine-readable reason (SYNC.md section 5.3). Open set: clients treat an unknown code as a generic permanent rejection. | 
**message** | **str** | English diagnostic for logs and the sync-status screen; not for parsing. | 

## Example

```python
from kotobaya_client.models.push_result_error import PushResultError

# TODO update the JSON string below
json = "{}"
# create an instance of PushResultError from a JSON string
push_result_error_instance = PushResultError.from_json(json)
# print the JSON string representation of the object
print(PushResultError.to_json())

# convert the object into a dict
push_result_error_dict = push_result_error_instance.to_dict()
# create an instance of PushResultError from a dict
push_result_error_from_dict = PushResultError.from_dict(push_result_error_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


