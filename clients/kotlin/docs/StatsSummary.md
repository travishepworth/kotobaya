
# StatsSummary

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **dayEndsAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Instant the study day rolls over. |  |
| **doneToday** | [**StatsSummaryDoneToday**](StatsSummaryDoneToday.md) |  |  |
| **due** | [**StatsSummaryDue**](StatsSummaryDue.md) |  |  |
| **nextDueAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Earliest &#x60;due_at&#x60; in the future among live unsuspended cards; null if none. |  |
| **streakDays** | **kotlin.Int** | Consecutive study days with at least one review, ending today or yesterday. |  |
| **studyDay** | [**java.time.LocalDate**](java.time.LocalDate.md) | The current study day. |  |
| **totals** | [**StatsSummaryTotals**](StatsSummaryTotals.md) |  |  |



