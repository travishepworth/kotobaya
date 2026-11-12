# ExportDump

Full data dump.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**dict_release_version** | **str** | Dictionary release the entry references belong to. | 
**exported_at** | **datetime** | Snapshot time. | 
**format_version** | **int** | Version of this dump format. | 
**high_water_seq** | **int** | Sync head of the snapshot. | 
**server_version** | **str** | Server build version. | 
**tables** | **Dict[str, List[Dict[str, object]]]** | Rows per table, keyed by table name without schema: &#x60;note_type&#x60;, &#x60;note&#x60;, &#x60;card&#x60;, &#x60;review_log&#x60;, &#x60;word_status&#x60;, &#x60;deck&#x60;, &#x60;deck_item&#x60;, &#x60;confusable&#x60;, &#x60;grammar_point&#x60;, &#x60;sentence_grammar_span&#x60; (manual rows), &#x60;settings&#x60;, &#x60;fsrs_params&#x60;, &#x60;media&#x60;, &#x60;parse_override&#x60;; with &#x60;include&#x3D;media_content&#x60; also &#x60;media_segment&#x60;, &#x60;sentence&#x60;, &#x60;occurrence&#x60; and the detected rows of &#x60;sentence_grammar_span&#x60;. Each row is an object keyed by column name. Row order: primary key ascending. | 

## Example

```python
from kotobaya_client.models.export_dump import ExportDump

# TODO update the JSON string below
json = "{}"
# create an instance of ExportDump from a JSON string
export_dump_instance = ExportDump.from_json(json)
# print the JSON string representation of the object
print(ExportDump.to_json())

# convert the object into a dict
export_dump_dict = export_dump_instance.to_dict()
# create an instance of ExportDump from a dict
export_dump_from_dict = ExportDump.from_dict(export_dump_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


