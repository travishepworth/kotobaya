# GrammarRuleTestRequest

What to test.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**limit** | **int** | Stop after this many matching sentences. | [optional] [default to 50]
**media_id** | **str** | Only scan this media. | [optional] 
**rules** | [**List[DetectionRule]**](DetectionRule.md) | Rules to evaluate (drafts included). Default - the point&#39;s stored rules. | [optional] 

## Example

```python
from kotobaya_client.models.grammar_rule_test_request import GrammarRuleTestRequest

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarRuleTestRequest from a JSON string
grammar_rule_test_request_instance = GrammarRuleTestRequest.from_json(json)
# print the JSON string representation of the object
print(GrammarRuleTestRequest.to_json())

# convert the object into a dict
grammar_rule_test_request_dict = grammar_rule_test_request_instance.to_dict()
# create an instance of GrammarRuleTestRequest from a dict
grammar_rule_test_request_from_dict = GrammarRuleTestRequest.from_dict(grammar_rule_test_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


