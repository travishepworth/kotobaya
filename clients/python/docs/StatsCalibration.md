# StatsCalibration

Predicted versus actual recall.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**bins** | [**List[StatsCalibrationBinsInner]**](StatsCalibrationBinsInner.md) | Non-empty bins, ascending. | 
**var_from** | **date** | First day of the range. | 
**preset_id** | **str** | Echo of the filter. | 
**review_count** | **int** | Reviews considered. | 
**to** | **date** | Last day of the range. | 

## Example

```python
from kotobaya_client.models.stats_calibration import StatsCalibration

# TODO update the JSON string below
json = "{}"
# create an instance of StatsCalibration from a JSON string
stats_calibration_instance = StatsCalibration.from_json(json)
# print the JSON string representation of the object
print(StatsCalibration.to_json())

# convert the object into a dict
stats_calibration_dict = stats_calibration_instance.to_dict()
# create an instance of StatsCalibration from a dict
stats_calibration_from_dict = StatsCalibration.from_dict(stats_calibration_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


