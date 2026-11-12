# CardBulkScheduleRequest

Selector plus a schedule action. Exactly one of `card_ids` and `query` (as `CardBulkRequest`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**action** | [**CardBulkScheduleRequestAction**](CardBulkScheduleRequestAction.md) |  | 
**card_ids** | **List[str]** | Explicit card ids. | [optional] 
**dry_run** | **bool** | Report counts without changing anything. | [optional] [default to False]
**query** | **str** | Card search string. | [optional] 

## Example

```python
from kotobaya_client.models.card_bulk_schedule_request import CardBulkScheduleRequest

# TODO update the JSON string below
json = "{}"
# create an instance of CardBulkScheduleRequest from a JSON string
card_bulk_schedule_request_instance = CardBulkScheduleRequest.from_json(json)
# print the JSON string representation of the object
print(CardBulkScheduleRequest.to_json())

# convert the object into a dict
card_bulk_schedule_request_dict = card_bulk_schedule_request_instance.to_dict()
# create an instance of CardBulkScheduleRequest from a dict
card_bulk_schedule_request_from_dict = CardBulkScheduleRequest.from_dict(card_bulk_schedule_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


