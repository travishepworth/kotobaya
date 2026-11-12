# ReviewUndoneSchema

Marks a review undone. The EARLIEST undo (smallest hlc) wins. An undo cannot be undone. OPS.md#reviewundone

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**review_id** | **str** | review_log.id to undo. Equals entity_id. Must exist on the server. | 

## Example

```python
from kotobaya_client.models.review_undone_schema import ReviewUndoneSchema

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewUndoneSchema from a JSON string
review_undone_schema_instance = ReviewUndoneSchema.from_json(json)
# print the JSON string representation of the object
print(ReviewUndoneSchema.to_json())

# convert the object into a dict
review_undone_schema_dict = review_undone_schema_instance.to_dict()
# create an instance of ReviewUndoneSchema from a dict
review_undone_schema_from_dict = ReviewUndoneSchema.from_dict(review_undone_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


