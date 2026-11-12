# StatsKnownWordsCurrent

Entries by effective status now (`new` counts only entries that have a `word_status` row).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**blacklisted** | **int** | Status &#x60;blacklisted&#x60;. | 
**ignored** | **int** | Status &#x60;ignored&#x60;. | 
**known** | **int** | Status &#x60;known&#x60;. | 
**learning** | **int** | Status &#x60;learning&#x60;. | 
**never_forget** | **int** | Status &#x60;never_forget&#x60;. | 
**new** | **int** | Status &#x60;new&#x60;. | 

## Example

```python
from kotobaya_client.models.stats_known_words_current import StatsKnownWordsCurrent

# TODO update the JSON string below
json = "{}"
# create an instance of StatsKnownWordsCurrent from a JSON string
stats_known_words_current_instance = StatsKnownWordsCurrent.from_json(json)
# print the JSON string representation of the object
print(StatsKnownWordsCurrent.to_json())

# convert the object into a dict
stats_known_words_current_dict = stats_known_words_current_instance.to_dict()
# create an instance of StatsKnownWordsCurrent from a dict
stats_known_words_current_from_dict = StatsKnownWordsCurrent.from_dict(stats_known_words_current_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


