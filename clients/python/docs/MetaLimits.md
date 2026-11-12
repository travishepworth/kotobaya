# MetaLimits

Request limits enforced by this server.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**max_blob_bytes** | **int** | Maximum blob size. | 
**max_json_bytes** | **int** | Maximum size of any other JSON request body. | 
**max_op_bytes** | **int** | Maximum size (bytes) of one op: the UTF-8 length of the op envelope serialized as compact JSON, as it appears in &#x60;PushRequest.ops&#x60; (SYNC.md 5.4). Default 1 MiB (&#x60;1048576&#x60;). Invariant: &#x60;max_op_bytes &lt;&#x3D; max_push_bytes - 4096&#x60;, so a batch holding one maximal op always fits in a push body. Clients MUST NOT mint a larger op (SYNC.md 4); a client that has not read &#x60;/meta&#x60; yet uses the default. A pushed op over the limit is rejected **per op** with &#x60;op_too_large&#x60; (never a whole-request &#x60;413&#x60;).  | 
**max_page_size** | **int** | Maximum &#x60;limit&#x60; of paginated lists. | 
**max_pull_ops** | **int** | Maximum &#x60;limit&#x60; of &#x60;GET /sync/pull&#x60;; fixed by &#x60;PullResponse.ops.maxItems&#x60;. | 
**max_push_bytes** | **int** | Maximum body size (bytes) of &#x60;POST /sync/push&#x60;; a larger body is &#x60;413 payload_too_large&#x60; (the client then halves the batch, SYNC.md 5.4). Default 8 MiB (&#x60;8388608&#x60;). Also the size a &#x60;GET /sync/pull&#x60; page SHOULD stay under (SYNC.md 6.1).  | 
**max_push_ops** | **int** | Maximum ops per &#x60;POST /sync/push&#x60;; fixed by &#x60;PushRequest.ops.maxItems&#x60;. | 

## Example

```python
from kotobaya_client.models.meta_limits import MetaLimits

# TODO update the JSON string below
json = "{}"
# create an instance of MetaLimits from a JSON string
meta_limits_instance = MetaLimits.from_json(json)
# print the JSON string representation of the object
print(MetaLimits.to_json())

# convert the object into a dict
meta_limits_dict = meta_limits_instance.to_dict()
# create an instance of MetaLimits from a dict
meta_limits_from_dict = MetaLimits.from_dict(meta_limits_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


