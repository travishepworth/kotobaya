# ReviewUndoRequest

Which review to undo.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**review_id** | **str** | The review to undo (&#x60;review_log.id&#x60;). | 

## Example

```python
from kotobaya_client.models.review_undo_request import ReviewUndoRequest

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewUndoRequest from a JSON string
review_undo_request_instance = ReviewUndoRequest.from_json(json)
# print the JSON string representation of the object
print(ReviewUndoRequest.to_json())

# convert the object into a dict
review_undo_request_dict = review_undo_request_instance.to_dict()
# create an instance of ReviewUndoRequest from a dict
review_undo_request_from_dict = ReviewUndoRequest.from_dict(review_undo_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


