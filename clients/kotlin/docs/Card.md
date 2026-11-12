
# Card

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **buriedUntil** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Manual bury - hidden while now is before this instant. Sibling burying is not stored. |  |
| **createdAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Creation time. |  |
| **deletedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Tombstone. |  |
| **difficulty** | [**java.math.BigDecimal**](java.math.BigDecimal.md) | FSRS difficulty; null for new cards. |  |
| **dueAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | When the card becomes due; null iff &#x60;state&#x60; is &#x60;new&#x60;. |  |
| **flags** | **kotlin.Int** | 0 &#x3D; none, 1-7 &#x3D; flag colour. |  |
| **id** | **kotlin.String** | Card id. |  |
| **intervalDays** | **kotlin.Int** | Interval scheduled by the last review (0 during learning steps); null for new cards. |  |
| **kind** | **kotlin.String** | Card kind - a key of the note type&#39;s &#x60;templates&#x60;. |  |
| **lapses** | **kotlin.Int** | Number of Again ratings while in state &#x60;review&#x60;. |  |
| **lastReviewAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Time of the last schedule-feeding review. |  |
| **leech** | **kotlin.Boolean** | Derived - &#x60;lapses &gt;&#x3D; scheduler.leech_threshold&#x60;. |  |
| **mergedIntoId** | **kotlin.String** | Set on a merge loser - the survivor&#39;s id. |  |
| **noteId** | **kotlin.String** | Owning note. |  |
| **presetId** | **kotlin.String** | Scheduling preset. If it is deleted, the Default preset schedules the card. |  |
| **reps** | **kotlin.Int** | Number of scheduled/learning reviews. |  |
| **stability** | [**java.math.BigDecimal**](java.math.BigDecimal.md) | FSRS stability in days; null for new cards. |  |
| **state** | [**CardState**](CardState.md) |  |  |
| **step** | **kotlin.Int** | Index into the (re)learning steps while learning/relearning; else null. |  |
| **suspended** | **kotlin.Boolean** | Suspended cards are never queued but keep their schedule. |  |
| **updatedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Last change of a user register (not of the schedule cache). |  |
| **note** | [**NoteSummary**](NoteSummary.md) | Summary of the owning note. Omitted when the card is embedded in a &#x60;Note&#x60;. |  [optional] |



