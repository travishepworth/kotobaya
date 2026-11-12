
# PushResult

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **opId** | **kotlin.String** | The op this result is for. |  |
| **status** | [**inline**](#Status) | accepted: applied and logged now. duplicate: this op_id was already in the log; nothing was done. rejected: permanently refused; nothing was done. |  |
| **error** | [**PushResultError**](PushResultError.md) |  |  [optional] |
| **seq** | **kotlin.Int** | change.seq of the op. Present iff status is accepted or duplicate. |  [optional] |


<a id="Status"></a>
## Enum: status
| Name | Value |
| ---- | ----- |
| status | accepted, duplicate, rejected |



