
# DrillSession

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **id** | **kotlin.String** | Fresh UUIDv7; not stored by the server. |  |
| **items** | [**kotlin.collections.List&lt;QueueItem&gt;**](QueueItem.md) | Cards to drill, in order (&#x60;preview&#x60; is null). |  |
| **mode** | [**inline**](#Mode) | Pass as &#x60;mode&#x60; to &#x60;POST /review/answer&#x60;. |  |


<a id="Mode"></a>
## Enum: mode
| Name | Value |
| ---- | ----- |
| mode | drill, cram |



