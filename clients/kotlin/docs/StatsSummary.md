
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
| **moreAvailable** | **kotlin.Boolean** | C-26 (additive). The same signal as &#x60;ReviewQueue.more_available&#x60; for an unfiltered normal &#x60;getReviewQueue&#x60; (no &#x60;deck_id&#x60;, no &#x60;kinds&#x60;, &#x60;keep_going&#x3D;false&#x60;): true iff the normal queue is empty and a keep-going queue would not be (the core&#39;s &#x60;ReviewQueue.moreAvailable&#x60; of the build behind &#x60;due&#x60;). Lets the Today page show Keep going without an extra queue call. As there, true may mean only that the per-session new-card cap is reached (\&quot;done for now\&quot;, not necessarily \&quot;done for today\&quot;). Servers implementing C-26 always send it; absent (older server) is treated as false.  |  [optional] |



