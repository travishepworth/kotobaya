# SchedulePreview

Next schedule per rating, computed by the core for an answer given now.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**again** | [**SchedulePreviewOption**](SchedulePreviewOption.md) |  | 
**easy** | [**SchedulePreviewOption**](SchedulePreviewOption.md) |  | 
**good** | [**SchedulePreviewOption**](SchedulePreviewOption.md) |  | 
**hard** | [**SchedulePreviewOption**](SchedulePreviewOption.md) |  | 

## Example

```python
from kotobaya_client.models.schedule_preview import SchedulePreview

# TODO update the JSON string below
json = "{}"
# create an instance of SchedulePreview from a JSON string
schedule_preview_instance = SchedulePreview.from_json(json)
# print the JSON string representation of the object
print(SchedulePreview.to_json())

# convert the object into a dict
schedule_preview_dict = schedule_preview_instance.to_dict()
# create an instance of SchedulePreview from a dict
schedule_preview_from_dict = SchedulePreview.from_dict(schedule_preview_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


