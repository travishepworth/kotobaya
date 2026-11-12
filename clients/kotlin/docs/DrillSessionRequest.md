
# DrillSessionRequest

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **source** | [**inline**](#Source) | Where the cards come from. |  |
| **cardIds** | **kotlin.collections.Set&lt;kotlin.String&gt;** | &#x60;cards&#x60; only (required there). |  [optional] |
| **entrySeq** | **kotlin.Long** | &#x60;confusables&#x60; only - restrict to pairs of this entry&#39;s note. |  [optional] |
| **limit** | **kotlin.Int** | Maximum number of cards. |  [optional] |
| **mode** | [**inline**](#Mode) | Review type the answers will be logged with. |  [optional] |
| **query** | **kotlin.String** | &#x60;query&#x60; only (required there) - card search string. |  [optional] |


<a id="Source"></a>
## Enum: source
| Name | Value |
| ---- | ----- |
| source | hot_words, leeches, confusables, query, cards |


<a id="Mode"></a>
## Enum: mode
| Name | Value |
| ---- | ----- |
| mode | drill, cram |



