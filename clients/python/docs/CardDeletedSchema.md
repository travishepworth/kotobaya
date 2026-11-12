# CardDeletedSchema

Tombstones a card (permanent). Its reviews stay in review_log. OPS.md#carddeleted

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the card. Equals the envelope&#39;s entity_id. | 

## Example

```python
from kotobaya_client.models.card_deleted_schema import CardDeletedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of CardDeletedSchema from a JSON string
card_deleted_schema_instance = CardDeletedSchema.from_json(json)
# print the JSON string representation of the object
print(CardDeletedSchema.to_json())

# convert the object into a dict
card_deleted_schema_dict = card_deleted_schema_instance.to_dict()
# create an instance of CardDeletedSchema from a dict
card_deleted_schema_from_dict = CardDeletedSchema.from_dict(card_deleted_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


