# PushRequest

Body of POST /sync/push.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**ops** | [**List[PushRequestOpsInner]**](PushRequestOpsInner.md) | Ops minted by ONE device (all device_id equal), in strictly ascending hlc. The server processes them in array order. | 

## Example

```python
from kotobaya_client.models.push_request import PushRequest

# TODO update the JSON string below
json = "{}"
# create an instance of PushRequest from a JSON string
push_request_instance = PushRequest.from_json(json)
# print the JSON string representation of the object
print(PushRequest.to_json())

# convert the object into a dict
push_request_dict = push_request_instance.to_dict()
# create an instance of PushRequest from a dict
push_request_from_dict = PushRequest.from_dict(push_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


