# GrammarRuleTestResult

Rule test outcome.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**matches** | [**List[GrammarRuleTestResultMatchesInner]**](GrammarRuleTestResultMatchesInner.md) | Matching sentences in scan order. | 
**scanned** | **int** | Sentences examined. | 
**truncated** | **bool** | true when the scan stopped at &#x60;limit&#x60; or at the scan cap before seeing every sentence. | 

## Example

```python
from kotobaya_client.models.grammar_rule_test_result import GrammarRuleTestResult

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarRuleTestResult from a JSON string
grammar_rule_test_result_instance = GrammarRuleTestResult.from_json(json)
# print the JSON string representation of the object
print(GrammarRuleTestResult.to_json())

# convert the object into a dict
grammar_rule_test_result_dict = grammar_rule_test_result_instance.to_dict()
# create an instance of GrammarRuleTestResult from a dict
grammar_rule_test_result_from_dict = GrammarRuleTestResult.from_dict(grammar_rule_test_result_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


