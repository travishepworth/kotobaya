# SentenceToken

One parser token of a sentence: token format 1, `contracts/api/PARSER.md` section 1 (C-12), the same value as `app.sentence.tokens` elements and `contracts/api/token.schema.json`. Servers send all ten properties; clients must tolerate unknown ones (later optional additions). Within a sentence tokens are ascending and non-overlapping, offsets are UTF-16 code units into the ORIGINAL sentence text, and `surface` equals that slice; gaps are punctuation and whitespace. 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**cform** | **str** | Sudachi conjugation form verbatim (e.g. &#x60;連用形-一般&#x60;); null if the word does not inflect. | 
**var_class** | **str** | Coverage filter class, derived by the table in PARSER.md section 2 (same values as &#x60;occurrence.pos_class&#x60;). | 
**e** | **int** | End offset (exclusive). | 
**lemma** | **str** | Dictionary form used for matching (deinflected, from the normalised text). | 
**override** | **bool** | true when a parse override produced this token. | 
**pos** | **List[str]** | Sudachi part-of-speech levels 1-4 with &#x60;*&#x60; removed; empty only for override tokens. | 
**reading** | **str** | Hiragana reading of the surface (Sudachi reading, katakana folded to hiragana); may be empty only for override tokens. | 
**s** | **int** | Start offset in the sentence text (UTF-16 code units, inclusive). | 
**seq** | **int** | Matched dictionary entry; null &#x3D; unmapped. | 
**surface** | **str** | The original text &#x60;[s, e)&#x60; (never normalised). | 

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


