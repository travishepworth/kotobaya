# CardBulkRequest

Selector plus action. Exactly one of `card_ids` and `query`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**action** | [**CardBulkAction**](CardBulkAction.md) |  | 
**card_ids** | **List[str]** | Explicit card ids. | [optional] 
**dry_run** | **bool** | Report counts without changing anything. | [optional] [default to False]
**query** | **str** | Card search string. | [optional] 

## Example

```python
from kotobaya_client.models.card_bulk_request import CardBulkRequest

# TODO update the JSON string below
json = "{}"
# create an instance of CardBulkRequest from a JSON string
card_bulk_request_instance = CardBulkRequest.from_json(json)
# print the JSON string representation of the object
print(CardBulkRequest.to_json())

# convert the object into a dict
card_bulk_request_dict = card_bulk_request_instance.to_dict()
# create an instance of CardBulkRequest from a dict
card_bulk_request_from_dict = CardBulkRequest.from_dict(card_bulk_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


