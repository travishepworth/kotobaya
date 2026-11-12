
# CardBulkAction

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **type** | [**inline**](#Type) | What to do. |  |
| **flags** | **kotlin.Int** | &#x60;flag&#x60; - the flag value. |  [optional] |
| **fromDeckId** | **kotlin.String** | &#x60;move&#x60; - user deck to remove the items from. |  [optional] |
| **presetId** | **kotlin.String** | &#x60;set_preset&#x60; - the preset. |  [optional] |
| **tags** | **kotlin.collections.List&lt;kotlin.String&gt;** | &#x60;add_tags&#x60; / &#x60;remove_tags&#x60; - the tags. |  [optional] |
| **toDeckId** | **kotlin.String** | &#x60;move&#x60; - destination user deck. |  [optional] |
| **until** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | &#x60;bury&#x60; - bury until this instant (must be in the future). |  [optional] |


<a id="Type"></a>
## Enum: type
| Name | Value |
| ---- | ----- |
| type | suspend, unsuspend, bury, unbury, flag, set_preset, add_tags, remove_tags, move, delete |



