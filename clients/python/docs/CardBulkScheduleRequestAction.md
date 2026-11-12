# CardBulkScheduleRequestAction

`reschedule` needs `due_at`; `reset` takes no params.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**due_at** | **datetime** | &#x60;reschedule&#x60; - the new due instant. | [optional] 
**type** | **str** | What to do. | 

## Example

```python
from kotobaya_client.models.card_bulk_schedule_request_action import CardBulkScheduleRequestAction

# TODO update the JSON string below
json = "{}"
# create an instance of CardBulkScheduleRequestAction from a JSON string
card_bulk_schedule_request_action_instance = CardBulkScheduleRequestAction.from_json(json)
# print the JSON string representation of the object
print(CardBulkScheduleRequestAction.to_json())

# convert the object into a dict
card_bulk_schedule_request_action_dict = card_bulk_schedule_request_action_instance.to_dict()
# create an instance of CardBulkScheduleRequestAction from a dict
card_bulk_schedule_request_action_from_dict = CardBulkScheduleRequestAction.from_dict(card_bulk_schedule_request_action_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


