# ReviewPage

A page of review log rows.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[ReviewLog]**](ReviewLog.md) | Reviews, newest first. | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.review_page import ReviewPage

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewPage from a JSON string
review_page_instance = ReviewPage.from_json(json)
# print the JSON string representation of the object
print(ReviewPage.to_json())

# convert the object into a dict
review_page_dict = review_page_instance.to_dict()
# create an instance of ReviewPage from a dict
review_page_from_dict = ReviewPage.from_dict(review_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


