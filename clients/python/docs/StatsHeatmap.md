# StatsHeatmap

Reviews per study day.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**days** | [**List[StatsHeatmapDaysInner]**](StatsHeatmapDaysInner.md) | Days with at least one review, ascending. | 
**var_from** | **date** | First day of the range. | 
**to** | **date** | Last day of the range. | 

## Example

```python
from kotobaya_client.models.stats_heatmap import StatsHeatmap

# TODO update the JSON string below
json = "{}"
# create an instance of StatsHeatmap from a JSON string
stats_heatmap_instance = StatsHeatmap.from_json(json)
# print the JSON string representation of the object
print(StatsHeatmap.to_json())

# convert the object into a dict
stats_heatmap_dict = stats_heatmap_instance.to_dict()
# create an instance of StatsHeatmap from a dict
stats_heatmap_from_dict = StatsHeatmap.from_dict(stats_heatmap_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


