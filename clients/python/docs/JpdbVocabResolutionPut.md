# JpdbVocabResolutionPut

A correction. `entry_seq` is required for `mapped` and forbidden for `ignored` (`422`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**entry_seq** | **int** | The JMdict entry (&#x60;mapped&#x60; only); must exist and not be retired. | [optional] 
**resolution** | **str** | What imports do with the vid. | 

## Example

```python
from kotobaya_client.models.jpdb_vocab_resolution_put import JpdbVocabResolutionPut

# TODO update the JSON string below
json = "{}"
# create an instance of JpdbVocabResolutionPut from a JSON string
jpdb_vocab_resolution_put_instance = JpdbVocabResolutionPut.from_json(json)
# print the JSON string representation of the object
print(JpdbVocabResolutionPut.to_json())

# convert the object into a dict
jpdb_vocab_resolution_put_dict = jpdb_vocab_resolution_put_instance.to_dict()
# create an instance of JpdbVocabResolutionPut from a dict
jpdb_vocab_resolution_put_from_dict = JpdbVocabResolutionPut.from_dict(jpdb_vocab_resolution_put_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


