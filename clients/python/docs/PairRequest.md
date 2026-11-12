# PairRequest

Device registration.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**code** | **str** | The pairing code. | 
**device_id** | **str** | UUIDv7 that is the node id of the device&#39;s HLC. Generated at first launch and on every loss or restore of the local database (reinstall, cleared data, restored backup); reused only by the same intact install when re-pairing (contracts/sync/SYNC.md 3.3). | 
**hlc_last** | **str** | The device&#39;s persisted &#x60;sync_state.hlc_last&#x60; (SYNC.md 3.2), sent when re-pairing an existing &#x60;device_id&#x60;. Its device part must equal &#x60;device_id&#x60;. If it is below the greatest HLC the server has logged for that device, pairing fails with &#x60;422&#x60; (&#x60;hlc_regressed&#x60;) and the client must pair as a new device. Omitted by a fresh install. | [optional] 
**kind** | **str** | Device kind. | [optional] [default to 'android']
**name** | **str** | Display name. | 
**push_topic** | **str** | UnifiedPush endpoint, if already known. | [optional] 

## Example

```python
from kotobaya_client.models.pair_request import PairRequest

# TODO update the JSON string below
json = "{}"
# create an instance of PairRequest from a JSON string
pair_request_instance = PairRequest.from_json(json)
# print the JSON string representation of the object
print(PairRequest.to_json())

# convert the object into a dict
pair_request_dict = pair_request_instance.to_dict()
# create an instance of PairRequest from a dict
pair_request_from_dict = PairRequest.from_dict(pair_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


