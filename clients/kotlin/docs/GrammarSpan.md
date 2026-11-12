
# GrammarSpan

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **createdAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Creation time. |  |
| **endOffset** | **kotlin.Int** | UTF-16 offset, exclusive. |  |
| **grammarPointId** | **kotlin.String** | Grammar point. |  |
| **mediaId** | **kotlin.String** | Media of the sentence, when known. |  |
| **origin** | [**SpanOrigin**](SpanOrigin.md) |  |  |
| **ruleIndex** | **kotlin.Int** | Detected spans - index of the matching rule; null for manual. |  |
| **sentenceId** | **kotlin.String** | Sentence (soft reference). |  |
| **startOffset** | **kotlin.Int** | UTF-16 offset, inclusive. |  |



