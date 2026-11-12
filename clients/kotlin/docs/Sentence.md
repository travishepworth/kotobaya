
# Sentence

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **audioBlobHash** | **kotlin.String** | Audio clip blob. |  |
| **endMs** | **kotlin.Int** | Subtitle end. |  |
| **grammarSpans** | [**kotlin.collections.List&lt;GrammarSpanRef&gt;**](GrammarSpanRef.md) | Live spans (detected and manual) whose grammar point is live, by &#x60;start_offset&#x60;. |  |
| **id** | **kotlin.String** | Sentence id (stable across re-parses while the text is unchanged). |  |
| **imageBlobHash** | **kotlin.String** | Image blob (manga page crop, screenshot). |  |
| **location** | [**kotlin.collections.Map&lt;kotlin.String, kotlin.Any&gt;**](kotlin.Any.md) | Source position for non-timed media, e.g. &#x60;{\&quot;page\&quot;: 12, \&quot;box\&quot;: [x, y, w, h]}&#x60;. |  |
| **mediaId** | **kotlin.String** | Owning media. |  |
| **ordinal** | **kotlin.Int** | 0-based order within the segment. |  |
| **origin** | [**inline**](#Origin) | &#x60;generated&#x60; &#x3D; LLM-generated example; always label it as such. |  |
| **segmentId** | **kotlin.String** | Owning segment. |  |
| **startMs** | **kotlin.Int** | Subtitle start within the segment; null for text sources. |  |
| **text** | **kotlin.String** | Original text. |  |
| **tokens** | [**kotlin.collections.List&lt;SentenceToken&gt;**](SentenceToken.md) | Tokens in ascending, non-overlapping order; gaps are untokenised text. |  |
| **unknownCount** | **kotlin.Int** | Number of unknown words now, as defined on the &#x60;filter&#x60; parameter (computed at request time). |  |


<a id="Origin"></a>
## Enum: origin
| Name | Value |
| ---- | ----- |
| origin | media, generated |



