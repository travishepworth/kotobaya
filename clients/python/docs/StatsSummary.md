# StatsSummary

Today's numbers.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**day_ends_at** | **datetime** | Instant the study day rolls over. | 
**done_today** | [**StatsSummaryDoneToday**](StatsSummaryDoneToday.md) |  | 
**due** | [**StatsSummaryDue**](StatsSummaryDue.md) |  | 
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


