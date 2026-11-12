# StatsKnownWordsSeriesInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**var_date** | **date** | End of the period. | 
**known** | **int** | Entries known or never-forget by then. | 

## Example

```python
from kotobaya_client.models.stats_known_words_series_inner import StatsKnownWordsSeriesInner

# TODO update the JSON string below
json = "{}"
# create an instance of StatsKnownWordsSeriesInner from a JSON string
stats_known_words_series_inner_instance = StatsKnownWordsSeriesInner.from_json(json)
# print the JSON string representation of the object
print(StatsKnownWordsSeriesInner.to_json())

# convert the object into a dict
stats_known_words_series_inner_dict = stats_known_words_series_inner_instance.to_dict()
# create an instance of StatsKnownWordsSeriesInner from a dict
stats_known_words_series_inner_from_dict = StatsKnownWordsSeriesInner.from_dict(stats_known_words_series_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


