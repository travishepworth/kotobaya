# StatsForecastHoursInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**due** | **int** | Cards becoming due in that hour (the first element includes overdue cards). | 
**starts_at** | **datetime** | Start of the hour. | 

## Example

```python
from kotobaya_client.models.stats_forecast_hours_inner import StatsForecastHoursInner

# TODO update the JSON string below
json = "{}"
# create an instance of StatsForecastHoursInner from a JSON string
stats_forecast_hours_inner_instance = StatsForecastHoursInner.from_json(json)
# print the JSON string representation of the object
print(StatsForecastHoursInner.to_json())

# convert the object into a dict
stats_forecast_hours_inner_dict = stats_forecast_hours_inner_instance.to_dict()
# create an instance of StatsForecastHoursInner from a dict
stats_forecast_hours_inner_from_dict = StatsForecastHoursInner.from_dict(stats_forecast_hours_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


