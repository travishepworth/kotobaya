# StatsSummaryDoneToday

Work done in the current study day (undone and manual rows excluded).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**new_cards** | **int** | Cards that got their first schedule-feeding review today. | 
**retention** | **float** | Passed / total over today&#39;s &#x60;scheduled&#x60; reviews; null when there were none. | 
**reviews** | **int** | Reviews logged (all types except manual). | 
**time_ms** | **int** | Sum of &#x60;duration_ms&#x60;. | 

## Example

```python
from kotobaya_client.models.stats_summary_done_today import StatsSummaryDoneToday

# TODO update the JSON string below
json = "{}"
# create an instance of StatsSummaryDoneToday from a JSON string
stats_summary_done_today_instance = StatsSummaryDoneToday.from_json(json)
# print the JSON string representation of the object
print(StatsSummaryDoneToday.to_json())

# convert the object into a dict
stats_summary_done_today_dict = stats_summary_done_today_instance.to_dict()
# create an instance of StatsSummaryDoneToday from a dict
stats_summary_done_today_from_dict = StatsSummaryDoneToday.from_dict(stats_summary_done_today_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


