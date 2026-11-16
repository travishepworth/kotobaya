# StatsSummary

Today's numbers.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**day_ends_at** | **datetime** | Instant the study day rolls over. | 
**done_today** | [**StatsSummaryDoneToday**](StatsSummaryDoneToday.md) |  | 
**due** | [**StatsSummaryDue**](StatsSummaryDue.md) |  | 
**more_available** | **bool** | **Deprecated (ADR-037): use &#x60;loop.phase&#x60; of &#x60;getReviewQueue?limit&#x3D;1&#x60;.** Still sent with the frozen meaning below (core &#x60;LoopState.legacy.moreAvailable&#x60;); the per-session cap no longer exists. C-26 (additive). The same signal as &#x60;ReviewQueue.more_available&#x60; for an unfiltered normal &#x60;getReviewQueue&#x60; (no &#x60;deck_id&#x60;, no &#x60;kinds&#x60;, &#x60;keep_going&#x3D;false&#x60;): true iff the normal queue is empty and a keep-going queue would not be (the core&#39;s &#x60;ReviewQueue.moreAvailable&#x60; of the build behind &#x60;due&#x60;). Lets the Today page show Keep going without an extra queue call. As there, true may mean only that the per-session new-card cap is reached (\&quot;done for now\&quot;, not necessarily \&quot;done for today\&quot;). Servers implementing C-26 always send it; absent (older server) is treated as false.  | [optional] 
**next_due_at** | **datetime** | Earliest &#x60;due_at&#x60; in the future among live unsuspended cards; null if none. | 
**streak_days** | **int** | Consecutive study days with at least one review, ending today or yesterday. | 
**study_day** | **date** | The current study day. | 
**totals** | [**StatsSummaryTotals**](StatsSummaryTotals.md) |  | 

## Example

```python
from kotobaya_client.models.stats_summary import StatsSummary

# TODO update the JSON string below
json = "{}"
# create an instance of StatsSummary from a JSON string
stats_summary_instance = StatsSummary.from_json(json)
# print the JSON string representation of the object
print(StatsSummary.to_json())

# convert the object into a dict
stats_summary_dict = stats_summary_instance.to_dict()
# create an instance of StatsSummary from a dict
stats_summary_from_dict = StatsSummary.from_dict(stats_summary_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


