
# StatsRetention

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **from** | [**java.time.LocalDate**](java.time.LocalDate.md) | First day of the range. |  |
| **groupBy** | [**inline**](#GroupBy) | Echo of the grouping. |  |
| **groups** | [**kotlin.collections.List&lt;RetentionGroup&gt;**](RetentionGroup.md) | Non-empty groups, ascending by key. |  |
| **overall** | [**RetentionGroup**](RetentionGroup.md) | All groups together (&#x60;key&#x60; &#x3D; &#x60;all&#x60;). |  |
| **to** | [**java.time.LocalDate**](java.time.LocalDate.md) | Last day of the range. |  |


<a id="GroupBy"></a>
## Enum: group_by
| Name | Value |
| ---- | ----- |
| groupBy | card_kind, day, week, month |



