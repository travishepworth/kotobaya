# StatsHeatmapDaysInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**again** | **int** | Reviews rated 1. | 
**var_date** | **date** | Study day. | 
**new_cards** | **int** | Cards first reviewed that day. | 
**reviews** | **int** | Reviews of every type except manual. | 
**time_ms** | **int** | Sum of &#x60;duration_ms&#x60;. | 

## Example

```python
from kotobaya_client.models.stats_heatmap_days_inner import StatsHeatmapDaysInner

# TODO update the JSON string below
json = "{}"
# create an instance of StatsHeatmapDaysInner from a JSON string
stats_heatmap_days_inner_instance = StatsHeatmapDaysInner.from_json(json)
# print the JSON string representation of the object
print(StatsHeatmapDaysInner.to_json())

# convert the object into a dict
stats_heatmap_days_inner_dict = stats_heatmap_days_inner_instance.to_dict()
# create an instance of StatsHeatmapDaysInner from a dict
stats_heatmap_days_inner_from_dict = StatsHeatmapDaysInner.from_dict(stats_heatmap_days_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


