# JpdbVocab

A jpdb vocabulary item that the automatic mapper could not map (`app.jpdb_vocab`, C-11).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**entry_seq** | **int** | The JMdict entry it is mapped to; set iff &#x60;resolution&#x60; is &#x60;mapped&#x60;. | 
**first_seen_at** | **datetime** | When an import first reported it. | 
**jpdb_vid** | **int** | jpdb vocabulary id. | 
**media** | [**List[JpdbVocabMedia]**](JpdbVocabMedia.md) | Live media containing it, by &#x60;occurrence_count&#x60; descending then &#x60;media_id&#x60;. | 
**occurrence_total** | **int** | Sum of &#x60;media[].occurrence_count&#x60; (live media only). | 
**reading** | **str** | Reading as jpdb gives it (latest import). | 
**resolution** | [**JpdbVocabResolution**](JpdbVocabResolution.md) |  | 
**resolved_at** | **datetime** | When the current resolution was set; null iff unresolved. | 
**spelling** | **str** | Spelling as jpdb gives it (latest import). | 

## Example

```python
from kotobaya_client.models.jpdb_vocab import JpdbVocab

# TODO update the JSON string below
json = "{}"
# create an instance of JpdbVocab from a JSON string
jpdb_vocab_instance = JpdbVocab.from_json(json)
# print the JSON string representation of the object
print(JpdbVocab.to_json())

# convert the object into a dict
jpdb_vocab_dict = jpdb_vocab_instance.to_dict()
# create an instance of JpdbVocab from a dict
jpdb_vocab_from_dict = JpdbVocab.from_dict(jpdb_vocab_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


