# FieldError

One validation problem.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**code** | **str** | One of &#x60;required&#x60;, &#x60;unknown_field&#x60;, &#x60;invalid_type&#x60;, &#x60;invalid_value&#x60;, &#x60;out_of_range&#x60;, &#x60;too_long&#x60;, &#x60;not_found&#x60; (referenced row missing), &#x60;immutable&#x60;, &#x60;conflicting_fields&#x60;, or a search-parser code such as &#x60;unknown_term&#x60;. | 
**detail** | **str** | Human-readable explanation. | 
**pointer** | **str** | JSON Pointer into the request body (&#x60;/fields/meaning&#x60;), or &#x60;/&lt;name&gt;&#x60; for a query or path parameter. | 
**position** | **int** | For search-string errors - UTF-16 offset of the error in the string. | [optional] 

## Example

```python
from kotobaya_client.models.field_error import FieldError

# TODO update the JSON string below
json = "{}"
# create an instance of FieldError from a JSON string
field_error_instance = FieldError.from_json(json)
# print the JSON string representation of the object
print(FieldError.to_json())

# convert the object into a dict
field_error_dict = field_error_instance.to_dict()
# create an instance of FieldError from a dict
field_error_from_dict = FieldError.from_dict(field_error_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


