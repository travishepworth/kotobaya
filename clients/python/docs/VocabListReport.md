# VocabListReport

How one vocabulary list's source rows were accounted for (C-21; `contracts/db/VOCAB_LISTS.md` 3.3).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**duplicates** | **int** | Rows matching an entry already in this list. | 
**entries** | **int** | Rows that became list items (&#x3D; &#x60;item_count&#x60; in &#x60;GET /deck-lists&#x60;). | 
**id** | **str** | List id &#x3D; deck &#x60;list_key&#x60;. | 
**shadowed** | **int** | Rows matching an entry already in an easier list of the family. | 
**source_rows** | **int** | Data rows in the source file. | 
**unmatched** | **int** | Rows matching no live entry (listed in &#x60;ref.vocab_list_unmatched&#x60;). | 

## Example

```python
from kotobaya_client.models.vocab_list_report import VocabListReport

# TODO update the JSON string below
json = "{}"
# create an instance of VocabListReport from a JSON string
vocab_list_report_instance = VocabListReport.from_json(json)
# print the JSON string representation of the object
print(VocabListReport.to_json())

# convert the object into a dict
vocab_list_report_dict = vocab_list_report_instance.to_dict()
# create an instance of VocabListReport from a dict
vocab_list_report_from_dict = VocabListReport.from_dict(vocab_list_report_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


