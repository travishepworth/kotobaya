# StatsRetention

Retention report.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**var_from** | **date** | First day of the range. | 
**group_by** | **str** | Echo of the grouping. | 
**groups** | [**List[RetentionGroup]**](RetentionGroup.md) | Non-empty groups, ascending by key. | 
**overall** | [**RetentionGroup**](RetentionGroup.md) | All groups together (&#x60;key&#x60; &#x3D; &#x60;all&#x60;). | 
**to** | **date** | Last day of the range. | 

## Example

```python
from kotobaya_client.models.stats_retention import StatsRetention

# TODO update the JSON string below
json = "{}"
# create an instance of StatsRetention from a JSON string
stats_retention_instance = StatsRetention.from_json(json)
# print the JSON string representation of the object
print(StatsRetention.to_json())

# convert the object into a dict
stats_retention_dict = stats_retention_instance.to_dict()
# create an instance of StatsRetention from a dict
stats_retention_from_dict = StatsRetention.from_dict(stats_retention_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


