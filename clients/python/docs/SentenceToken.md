# SentenceToken

One parser token of a sentence. Shape finalised by the parser contract; unknown properties must be tolerated.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**cform** | **str** | Conjugation form. | [optional] 
**var_class** | **str** | Coverage filter class. | [optional] 
**e** | **int** | End offset (exclusive). | 
**lemma** | **str** | Dictionary form. | [optional] 
**override** | **bool** | true when a parse override produced this token. | [optional] 
**pos** | **List[str]** | Sudachi part-of-speech hierarchy. | [optional] 
**reading** | **str** | Kana reading of the surface. | [optional] 
**s** | **int** | Start offset in the sentence text (UTF-16 code units, inclusive). | 
**seq** | **int** | Matched dictionary entry; null &#x3D; unmapped. | 
**surface** | **str** | Text of the token. | 

## Example

```python
from kotobaya_client.models.sentence_token import SentenceToken

# TODO update the JSON string below
json = "{}"
# create an instance of SentenceToken from a JSON string
sentence_token_instance = SentenceToken.from_json(json)
# print the JSON string representation of the object
print(SentenceToken.to_json())

# convert the object into a dict
sentence_token_dict = sentence_token_instance.to_dict()
# create an instance of SentenceToken from a dict
sentence_token_from_dict = SentenceToken.from_dict(sentence_token_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


