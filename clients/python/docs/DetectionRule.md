# DetectionRule

One detection rule of a grammar point.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**draft** | **bool** | Draft rules are kept and testable but not used by detection. | [optional] [default to False]
**rule** | **str** | Rule in the token-pattern language over Sudachi output (grammar owned by the core). | 

## Example

```python
from kotobaya_client.models.detection_rule import DetectionRule

# TODO update the JSON string below
json = "{}"
# create an instance of DetectionRule from a JSON string
detection_rule_instance = DetectionRule.from_json(json)
# print the JSON string representation of the object
print(DetectionRule.to_json())

# convert the object into a dict
detection_rule_dict = detection_rule_instance.to_dict()
# create an instance of DetectionRule from a dict
detection_rule_from_dict = DetectionRule.from_dict(detection_rule_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


