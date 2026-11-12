# DeckContentPublishedSchema

Announces a new server-built deck_item snapshot of a media or list deck. OPS.md#deckcontent_published

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**content_blob_hash** | **str** | Blob holding the snapshot for content_version. | 
**content_version** | **int** | New version of the deck_item snapshot; strictly positive. Applied iff &gt;&#x3D; the stored content_version. | 
**deck_id** | **str** | Deck. Equals entity_id. | 

## Example

```python
from kotobaya_client.models.deck_content_published_schema import DeckContentPublishedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of DeckContentPublishedSchema from a JSON string
deck_content_published_schema_instance = DeckContentPublishedSchema.from_json(json)
# print the JSON string representation of the object
print(DeckContentPublishedSchema.to_json())

# convert the object into a dict
deck_content_published_schema_dict = deck_content_published_schema_instance.to_dict()
# create an instance of DeckContentPublishedSchema from a dict
deck_content_published_schema_from_dict = DeckContentPublishedSchema.from_dict(deck_content_published_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


