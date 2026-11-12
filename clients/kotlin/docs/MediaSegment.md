
# MediaSegment

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **id** | **kotlin.String** | Segment id (stable across re-parses). |  |
| **kind** | [**inline**](#Kind) | Segment kind. |  |
| **mediaId** | **kotlin.String** | Owning media. |  |
| **ordinal** | **kotlin.Int** | 0-based order within the media. |  |
| **sentenceCount** | **kotlin.Int** | Sentences in the segment. |  |
| **title** | **kotlin.String** | Title; may be empty. |  |
| **tokenCount** | **kotlin.Int** | Word tokens in the segment. |  |
| **unmappedTokenCount** | **kotlin.Int** | Of those, tokens that matched no entry. |  |


<a id="Kind"></a>
## Enum: kind
| Name | Value |
| ---- | ----- |
| kind | episode, chapter, volume, page, whole |



