# WordStatus

Word status of an entry (`app.word_status`). `status = coalesce(override_status, auto_status, new)`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**auto_status** | **str** | Status derived from the entry&#39;s vocab cards; null &#x3D; no live vocab note. | 
**entry_seq** | **int** | JMdict sequence number (&#x60;ref.dict_entry.seq&#x60;). | 
**override_origin** | **str** | Who set the override. | 
**override_status** | **str** | Explicit override; null &#x3D; none. | 
**status** | [**WordStatusValue**](WordStatusValue.md) | Effective status. | 
**updated_at** | **datetime** | Last change; null when there is no row. | 

## Example

```python
from kotobaya_client.models.word_status import WordStatus

# TODO update the JSON string below
json = "{}"
# create an instance of WordStatus from a JSON string
word_status_instance = WordStatus.from_json(json)
# print the JSON string representation of the object
print(WordStatus.to_json())

# convert the object into a dict
word_status_dict = word_status_instance.to_dict()
# create an instance of WordStatus from a dict
word_status_from_dict = WordStatus.from_dict(word_status_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


