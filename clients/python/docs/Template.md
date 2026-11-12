# Template

One card kind of a note type. Unknown keys are preserved.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**back** | **str** | Back HTML; null &#x3D; the core&#39;s built-in template. | 
**default** | **bool** | true &#x3D; clients create this card automatically with a new note. | 
**front** | **str** | Front HTML with {{field_key}} placeholders; null &#x3D; the core&#39;s built-in template. | 

## Example

```python
from kotobaya_client.models.template import Template

# TODO update the JSON string below
json = "{}"
# create an instance of Template from a JSON string
template_instance = Template.from_json(json)
# print the JSON string representation of the object
print(Template.to_json())

# convert the object into a dict
template_dict = template_instance.to_dict()
# create an instance of Template from a dict
template_from_dict = Template.from_dict(template_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


