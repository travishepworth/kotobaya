# StatsForecastDaysInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**var_date** | **date** | Study day. | 
**due** | **int** | Cards becoming due that day (the first element includes overdue cards). | 

## Example

```python
from kotobaya_client.models.stats_forecast_days_inner import StatsForecastDaysInner

# TODO update the JSON string below
json = "{}"
# create an instance of StatsForecastDaysInner from a JSON string
stats_forecast_days_inner_instance = StatsForecastDaysInner.from_json(json)
# print the JSON string representation of the object
print(StatsForecastDaysInner.to_json())

# convert the object into a dict
stats_forecast_days_inner_dict = stats_forecast_days_inner_instance.to_dict()
# create an instance of StatsForecastDaysInner from a dict
stats_forecast_days_inner_from_dict = StatsForecastDaysInner.from_dict(stats_forecast_days_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


