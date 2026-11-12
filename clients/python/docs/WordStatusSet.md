# WordStatusSet

Override to set.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**status** | **str** | &#x60;never_forget&#x60; &#x3D; known, never reviewed; &#x60;blacklisted&#x60; &#x3D; ignored everywhere; &#x60;ignored&#x60; &#x3D; left out of coverage only. | 

## Example

```python
from kotobaya_client.models.word_status_set import WordStatusSet

# TODO update the JSON string below
json = "{}"
# create an instance of WordStatusSet from a JSON string
word_status_set_instance = WordStatusSet.from_json(json)
# print the JSON string representation of the object
print(WordStatusSet.to_json())

# convert the object into a dict
word_status_set_dict = word_status_set_instance.to_dict()
# create an instance of WordStatusSet from a dict
word_status_set_from_dict = WordStatusSet.from_dict(word_status_set_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


