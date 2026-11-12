# StatsKnownWords

Known words now and over time.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**current** | [**StatsKnownWordsCurrent**](StatsKnownWordsCurrent.md) |  | 
**series** | [**List[StatsKnownWordsSeriesInner]**](StatsKnownWordsSeriesInner.md) | Points ascending by date; the last point is &#x60;to&#x60;. | 

## Example

```python
from kotobaya_client.models.stats_known_words import StatsKnownWords

# TODO update the JSON string below
json = "{}"
# create an instance of StatsKnownWords from a JSON string
stats_known_words_instance = StatsKnownWords.from_json(json)
# print the JSON string representation of the object
print(StatsKnownWords.to_json())

# convert the object into a dict
stats_known_words_dict = stats_known_words_instance.to_dict()
# create an instance of StatsKnownWords from a dict
stats_known_words_from_dict = StatsKnownWords.from_dict(stats_known_words_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


