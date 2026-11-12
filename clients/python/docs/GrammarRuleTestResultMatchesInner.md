# GrammarRuleTestResultMatchesInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**sentence** | [**Sentence**](Sentence.md) |  | 
**spans** | [**List[GrammarRuleTestResultMatchesInnerSpansInner]**](GrammarRuleTestResultMatchesInnerSpansInner.md) | Matches in this sentence, by start offset. | 

## Example

```python
from kotobaya_client.models.grammar_rule_test_result_matches_inner import GrammarRuleTestResultMatchesInner

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarRuleTestResultMatchesInner from a JSON string
grammar_rule_test_result_matches_inner_instance = GrammarRuleTestResultMatchesInner.from_json(json)
# print the JSON string representation of the object
print(GrammarRuleTestResultMatchesInner.to_json())

# convert the object into a dict
grammar_rule_test_result_matches_inner_dict = grammar_rule_test_result_matches_inner_instance.to_dict()
# create an instance of GrammarRuleTestResultMatchesInner from a dict
grammar_rule_test_result_matches_inner_from_dict = GrammarRuleTestResultMatchesInner.from_dict(grammar_rule_test_result_matches_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


