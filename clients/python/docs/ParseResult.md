# ParseResult

Result of `POST /parse`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**entries** | [**List[DictEntrySummary]**](DictEntrySummary.md) | One summary per distinct mapped entry, ascending by &#x60;seq&#x60;. | 
**sentences** | [**List[ParsedSentence]**](ParsedSentence.md) | Sentences in input order. | 

## Example

```python
from kotobaya_client.models.parse_result import ParseResult

# TODO update the JSON string below
json = "{}"
# create an instance of ParseResult from a JSON string
parse_result_instance = ParseResult.from_json(json)
# print the JSON string representation of the object
print(ParseResult.to_json())

# convert the object into a dict
parse_result_dict = parse_result_instance.to_dict()
# create an instance of ParseResult from a dict
parse_result_from_dict = ParseResult.from_dict(parse_result_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


