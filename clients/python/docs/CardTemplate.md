# CardTemplate

One card kind of a note type.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**back** | **str** | As &#x60;front&#x60;, for the answer side. | 
**default** | **bool** | Create this card automatically with a new note. | 
**front** | **str** | HTML with &#x60;{{field_key}}&#x60; placeholders, or null &#x3D; the core&#39;s built-in template for (base type, kind). | 

## Example

```python
from kotobaya_client.models.card_template import CardTemplate

# TODO update the JSON string below
json = "{}"
# create an instance of CardTemplate from a JSON string
card_template_instance = CardTemplate.from_json(json)
# print the JSON string representation of the object
print(CardTemplate.to_json())

# convert the object into a dict
card_template_dict = card_template_instance.to_dict()
# create an instance of CardTemplate from a dict
card_template_from_dict = CardTemplate.from_dict(card_template_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


