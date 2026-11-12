# PushResponse

Body of a successful POST /sync/push response.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**durable_seq** | **int** | Highest seq s such that every change row with seq &lt;&#x3D; s is in a verified backup that any restore will start from (SYNC.md 6.2). &lt;&#x3D; high_water_seq; never decreases within one log_epoch. A device keeps each acked pending_op row until acked_seq &lt;&#x3D; durable_seq. | 
**high_water_seq** | **int** | Highest change.seq committed when the response was built; &gt;&#x3D; every seq in results. A client whose pull_seq is greater starts a log reset (SYNC.md 6.2); otherwise informational (pending pull count). | 
**log_epoch** | **str** | Identity of the server&#39;s log (SYNC.md 6.2). Changes only when the log is replaced (a restore from backup): seq values of different epochs are unrelated. A client that sees a value different from the one it stored starts a log reset. | 
**results** | [**List[PushResult]**](PushResult.md) | Exactly one result per pushed op, in request order. | 
**server_time** | **int** | Server wall clock when the response was built; clients compare it with their own clock to warn about skew and to block minting when it exceeds 24 h (SYNC.md 3.4). | 

## Example

```python
from kotobaya_client.models.push_response import PushResponse

# TODO update the JSON string below
json = "{}"
# create an instance of PushResponse from a JSON string
push_response_instance = PushResponse.from_json(json)
# print the JSON string representation of the object
print(PushResponse.to_json())

# convert the object into a dict
push_response_dict = push_response_instance.to_dict()
# create an instance of PushResponse from a dict
push_response_from_dict = PushResponse.from_dict(push_response_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


