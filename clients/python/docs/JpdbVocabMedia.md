# JpdbVocabMedia

One live media whose jpdb deck contains the vid.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**media_id** | **str** | The media. | 
**occurrence_count** | **int** | Occurrences of the vid in that media&#39;s jpdb deck, as of its latest import. | 
**title** | **str** | Its title. | 

## Example

```python
from kotobaya_client.models.jpdb_vocab_media import JpdbVocabMedia

# TODO update the JSON string below
json = "{}"
# create an instance of JpdbVocabMedia from a JSON string
jpdb_vocab_media_instance = JpdbVocabMedia.from_json(json)
# print the JSON string representation of the object
print(JpdbVocabMedia.to_json())

# convert the object into a dict
jpdb_vocab_media_dict = jpdb_vocab_media_instance.to_dict()
# create an instance of JpdbVocabMedia from a dict
jpdb_vocab_media_from_dict = JpdbVocabMedia.from_dict(jpdb_vocab_media_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


