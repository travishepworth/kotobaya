# SchedulePreviewOption

What one rating would do.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**due_at** | **datetime** | Next due instant (before fuzz). | 
**interval_days** | **int** | Resulting interval; 0 for a learning step. | 

## Example

```python
from kotobaya_client.models.schedule_preview_option import SchedulePreviewOption

# TODO update the JSON string below
json = "{}"
# create an instance of SchedulePreviewOption from a JSON string
schedule_preview_option_instance = SchedulePreviewOption.from_json(json)
# print the JSON string representation of the object
print(SchedulePreviewOption.to_json())

# convert the object into a dict
schedule_preview_option_dict = schedule_preview_option_instance.to_dict()
# create an instance of SchedulePreviewOption from a dict
schedule_preview_option_from_dict = SchedulePreviewOption.from_dict(schedule_preview_option_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


