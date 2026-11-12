# TatoebaSentence

A Tatoeba sentence pair (`ref.example_sentence`, CC BY 2.0 FR).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **int** | Tatoeba id of the Japanese sentence. | 
**text** | **str** | Japanese text. | 
**translation** | **str** | English translation; empty if none. | 

## Example

```python
from kotobaya_client.models.tatoeba_sentence import TatoebaSentence

# TODO update the JSON string below
json = "{}"
# create an instance of TatoebaSentence from a JSON string
tatoeba_sentence_instance = TatoebaSentence.from_json(json)
# print the JSON string representation of the object
print(TatoebaSentence.to_json())

# convert the object into a dict
tatoeba_sentence_dict = tatoeba_sentence_instance.to_dict()
# create an instance of TatoebaSentence from a dict
tatoeba_sentence_from_dict = TatoebaSentence.from_dict(tatoeba_sentence_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


