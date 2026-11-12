# PairResponse

Result of pairing.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**device** | [**Device**](Device.md) |  | 
**high_water_seq** | **int** | Current sync head; a new device pulls from 0 up to at least this. | 
**scopes** | [**List[Scope]**](Scope.md) | Always &#x60;[read, write, review]&#x60;. | 
**server_device_id** | **str** | The server device id. | 
**token** | **str** | The device token. Shown only here. | 
**token_id** | **str** | &#x60;api_token.id&#x60; of the new token. | 

## Example

```python
from kotobaya_client.models.pair_response import PairResponse

# TODO update the JSON string below
json = "{}"
# create an instance of PairResponse from a JSON string
pair_response_instance = PairResponse.from_json(json)
# print the JSON string representation of the object
print(PairResponse.to_json())

# convert the object into a dict
pair_response_dict = pair_response_instance.to_dict()
# create an instance of PairResponse from a dict
pair_response_from_dict = PairResponse.from_dict(pair_response_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


