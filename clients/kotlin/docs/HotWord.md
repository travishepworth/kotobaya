
# HotWord

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **cards** | [**kotlin.collections.List&lt;Card&gt;**](Card.md) | Live cards of the note (their &#x60;note&#x60; property is omitted). |  |
| **entry** | [**DictEntrySummary**](DictEntrySummary.md) | Dictionary summary, or null. |  |
| **entrySeq** | **kotlin.Long** | Its entry; null for unmatched vocab notes. |  |
| **failures14d** | **kotlin.Int** | Again ratings on its cards in the last 14 days (scheduled and learning reviews). |  |
| **note** | [**NoteSummary**](NoteSummary.md) |  |  |
| **noteId** | **kotlin.String** | The vocab note. |  |
| **score** | [**java.math.BigDecimal**](java.math.BigDecimal.md) | Hot-word score (core-defined scale; only the order is meaningful). |  |



