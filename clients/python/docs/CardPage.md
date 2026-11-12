# CardPage

A page of cards.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[Card]**](Card.md) | Cards in the requested order, each with &#x60;note&#x60;. | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.card_page import CardPage

# TODO update the JSON string below
json = "{}"
# create an instance of CardPage from a JSON string
card_page_instance = CardPage.from_json(json)
# print the JSON string representation of the object
print(CardPage.to_json())

# convert the object into a dict
card_page_dict = card_page_instance.to_dict()
# create an instance of CardPage from a dict
card_page_from_dict = CardPage.from_dict(card_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


