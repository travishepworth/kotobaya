# PullResponse

Body of GET /sync/pull?since=<seq>.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**durable_seq** | **int** | Highest seq s such that every change row with seq &lt;&#x3D; s is in a verified backup that any restore will start from (SYNC.md 6.2). &lt;&#x3D; high_water_seq; never decreases within one log_epoch. A device keeps each acked pending_op row until acked_seq &lt;&#x3D; durable_seq. | 
**has_more** | **bool** | true &#x3D; more ops exist after next_since; pull again immediately. | 
**high_water_seq** | **int** | As in PushResponse. | 
**log_epoch** | **str** | Identity of the server&#39;s log (SYNC.md 6.2). Changes only when the log is replaced (a restore from backup): seq values of different epochs are unrelated. A client that sees a value different from the one it stored starts a log reset. | 
**next_since** | **int** | Cursor for the next pull: seq of the last op returned, or the request&#39;s since when ops is empty. | 
**ops** | [**List[OpSchema]**](OpSchema.md) | Log entries with seq &gt; since, in strictly ascending seq, from every origin including the requester itself. | 
**server_time** | **int** | As in PushResponse. | 

## Example

```python
from kotobaya_client.models.pull_response import PullResponse

# TODO update the JSON string below
json = "{}"
# create an instance of PullResponse from a JSON string
pull_response_instance = PullResponse.from_json(json)
# print the JSON string representation of the object
print(PullResponse.to_json())

# convert the object into a dict
pull_response_dict = pull_response_instance.to_dict()
# create an instance of PullResponse from a dict
pull_response_from_dict = PullResponse.from_dict(pull_response_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


