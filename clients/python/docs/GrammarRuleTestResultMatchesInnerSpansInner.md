# GrammarRuleTestResultMatchesInnerSpansInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**end_offset** | **int** | UTF-16 offset, exclusive. | 
**rule_index** | **int** | Index into the evaluated rule list. | 
**start_offset** | **int** | UTF-16 offset, inclusive. | 

## Example

```python
from kotobaya_client.models.grammar_rule_test_result_matches_inner_spans_inner import GrammarRuleTestResultMatchesInnerSpansInner

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarRuleTestResultMatchesInnerSpansInner from a JSON string
grammar_rule_test_result_matches_inner_spans_inner_instance = GrammarRuleTestResultMatchesInnerSpansInner.from_json(json)
# print the JSON string representation of the object
print(GrammarRuleTestResultMatchesInnerSpansInner.to_json())

# convert the object into a dict
grammar_rule_test_result_matches_inner_spans_inner_dict = grammar_rule_test_result_matches_inner_spans_inner_instance.to_dict()
# create an instance of GrammarRuleTestResultMatchesInnerSpansInner from a dict
grammar_rule_test_result_matches_inner_spans_inner_from_dict = GrammarRuleTestResultMatchesInnerSpansInner.from_dict(grammar_rule_test_result_matches_inner_spans_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


