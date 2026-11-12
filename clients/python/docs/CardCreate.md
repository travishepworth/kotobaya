# CardCreate

New card for an existing note.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Optional client-generated UUIDv7 (idempotent creation). | [optional] 
**kind** | **str** | A card kind of the note&#39;s type. | 
**note_id** | **str** | The note. | 

## Example

```python
from kotobaya_client.models.card_create import CardCreate

# TODO update the JSON string below
json = "{}"
# create an instance of CardCreate from a JSON string
card_create_instance = CardCreate.from_json(json)
# print the JSON string representation of the object
print(CardCreate.to_json())

# convert the object into a dict
card_create_dict = card_create_instance.to_dict()
# create an instance of CardCreate from a dict
card_create_from_dict = CardCreate.from_dict(card_create_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


