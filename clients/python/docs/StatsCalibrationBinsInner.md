# StatsCalibrationBinsInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**actual** | **float** | Share of the bin&#39;s reviews that passed. | 
**count** | **int** | Reviews in the bin. | 
**lower** | **float** | Lower bound of predicted recall (inclusive). | 
**predicted** | **float** | Mean predicted recall in the bin. | 
**upper** | **float** | Upper bound (exclusive; inclusive for the last bin). | 

## Example

```python
from kotobaya_client.models.stats_calibration_bins_inner import StatsCalibrationBinsInner

# TODO update the JSON string below
json = "{}"
# create an instance of StatsCalibrationBinsInner from a JSON string
stats_calibration_bins_inner_instance = StatsCalibrationBinsInner.from_json(json)
# print the JSON string representation of the object
print(StatsCalibrationBinsInner.to_json())

# convert the object into a dict
stats_calibration_bins_inner_dict = stats_calibration_bins_inner_instance.to_dict()
# create an instance of StatsCalibrationBinsInner from a dict
stats_calibration_bins_inner_from_dict = StatsCalibrationBinsInner.from_dict(stats_calibration_bins_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


