# StatsForecast

Due forecast.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**days** | [**List[StatsForecastDaysInner]**](StatsForecastDaysInner.md) | One element per study day, starting today. | 
**hours** | [**List[StatsForecastHoursInner]**](StatsForecastHoursInner.md) | The next 24 clock hours. | 

## Example

```python
from kotobaya_client.models.stats_forecast import StatsForecast

# TODO update the JSON string below
json = "{}"
# create an instance of StatsForecast from a JSON string
stats_forecast_instance = StatsForecast.from_json(json)
# print the JSON string representation of the object
print(StatsForecast.to_json())

# convert the object into a dict
stats_forecast_dict = stats_forecast_instance.to_dict()
# create an instance of StatsForecast from a dict
stats_forecast_from_dict = StatsForecast.from_dict(stats_forecast_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


