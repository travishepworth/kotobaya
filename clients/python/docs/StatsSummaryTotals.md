# StatsSummaryTotals

Collection size.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**cards** | **int** | Live cards of live notes. | 
**leeches** | **int** | Of those, leeches. | 
**notes** | **int** | Live notes. | 
**suspended** | **int** | Of those, suspended. | 

## Example

```python
from kotobaya_client.models.stats_summary_totals import StatsSummaryTotals

# TODO update the JSON string below
json = "{}"
# create an instance of StatsSummaryTotals from a JSON string
stats_summary_totals_instance = StatsSummaryTotals.from_json(json)
# print the JSON string representation of the object
print(StatsSummaryTotals.to_json())

# convert the object into a dict
stats_summary_totals_dict = stats_summary_totals_instance.to_dict()
# create an instance of StatsSummaryTotals from a dict
stats_summary_totals_from_dict = StatsSummaryTotals.from_dict(stats_summary_totals_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


