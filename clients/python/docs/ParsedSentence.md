# ParsedSentence

One sentence of a parse result.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**text** | **str** | The sentence text, exactly as in the input. | 
**tokens** | [**List[SentenceToken]**](SentenceToken.md) | Tokens with offsets relative to &#x60;text&#x60;. | 

## Example

```python
from kotobaya_client.models.parsed_sentence import ParsedSentence

# TODO update the JSON string below
json = "{}"
# create an instance of ParsedSentence from a JSON string
parsed_sentence_instance = ParsedSentence.from_json(json)
# print the JSON string representation of the object
print(ParsedSentence.to_json())

# convert the object into a dict
parsed_sentence_dict = parsed_sentence_instance.to_dict()
# create an instance of ParsedSentence from a dict
parsed_sentence_from_dict = ParsedSentence.from_dict(parsed_sentence_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


