# PushRequestOpsInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**caused_by_op_id** | **str** | Pulled ops only: op_id of the op that made the server emit this one (merges, cascades), else null. | [optional] 
**device_id** | **str** | Origin device &#x3D; the device that minted hlc. Must equal the last 36 characters of hlc. | 
**entity_id** | **str** | Primary key of the targeted row as text, derived from the payload as listed in ops/index.json (parts joined with &#39;|&#39;). | 
**entity_type** | **str** | Table the op targets, fixed per op_type (ops/index.json). | 
**hlc** | **str** | Clock of the op. Decides every last-writer-wins comparison and supplies the op&#39;s wall time (first 12 hex digits). | 
**op_id** | **str** | UUIDv7 minted with the op. The idempotency key: the server applies an op_id at most once. | 
**op_type** | **str** | Dotted op name. The closed set of v1 op types. | 
**op_version** | **object** | Version of the payload schema for this op_type. Always 1 in this contract. | 
**payload** | **object** | Op-specific body; schema chosen by op_type. | 
**seq** | **int** | Server sequence number (change.seq). Present on every pulled op; MUST be absent on pushed ops. | [optional] 

## Example

```python
from kotobaya_client.models.push_request_ops_inner import PushRequestOpsInner

# TODO update the JSON string below
json = "{}"
# create an instance of PushRequestOpsInner from a JSON string
push_request_ops_inner_instance = PushRequestOpsInner.from_json(json)
# print the JSON string representation of the object
print(PushRequestOpsInner.to_json())

# convert the object into a dict
push_request_ops_inner_dict = push_request_ops_inner_instance.to_dict()
# create an instance of PushRequestOpsInner from a dict
push_request_ops_inner_from_dict = PushRequestOpsInner.from_dict(push_request_ops_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


