# Meta

Server information and limits.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**api_version** | **str** | API major version. | 
**core_version** | **str** | Version of the shared core the server schedules with. | 
**dict_release** | [**MetaDictRelease**](MetaDictRelease.md) |  | 
**high_water_seq** | **int** | Highest &#x60;change.seq&#x60; committed (0 &#x3D; empty log); same meaning as &#x60;PushResponse.high_water_seq&#x60;. | 
**limits** | [**MetaLimits**](MetaLimits.md) |  | 
**log_epoch** | **str** | Identity of the server&#39;s log (contracts/sync/SYNC.md 6.2); &#x60;high_water_seq&#x60; belongs to this epoch. | 
**server_device_id** | **str** | Origin device of server-built ops; always &#x60;00000000-0000-7000-8000-000000000001&#x60;. | 
**server_time** | **datetime** | Server clock. | 
**server_version** | **str** | Server build version. | 

## Example

```python
from kotobaya_client.models.meta import Meta

# TODO update the JSON string below
json = "{}"
# create an instance of Meta from a JSON string
meta_instance = Meta.from_json(json)
# print the JSON string representation of the object
print(Meta.to_json())

# convert the object into a dict
meta_dict = meta_instance.to_dict()
# create an instance of Meta from a dict
meta_from_dict = Meta.from_dict(meta_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


