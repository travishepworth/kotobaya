# JpdbDeckImportRequest

Which jpdb deck to import. Also the payload of `jpdb-deck-import` jobs, with `media_id` added.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**deck** | [**MediaDeckOptions**](MediaDeckOptions.md) |  | [optional] 
**jpdb_id** | **str** | jpdb deck identifier: the path of the deck page after &#x60;jpdb.io/&#x60; without the trailing &#x60;/vocabulary-list&#x60;, e.g. &#x60;anime/5370&#x60;, or a numeric deck id. | 
**kind** | [**MediaKind**](MediaKind.md) |  | [optional] 
**media_id** | **str** | Set by the server in the job payload; clients must not send it (&#x60;422&#x60;). | [optional] 
**remap_only** | **bool** | Set by the server in jobs that re-publish an existing jpdb media after a mapping correction (C-11, &#x60;contracts/db/MEDIA_PIPELINE.md&#x60; 5.4): use cached jpdb responses regardless of age, fetch only what the cache lacks; &#x60;title&#x60;, &#x60;kind&#x60;, &#x60;deck&#x60; are not used. Clients must not send it (&#x60;422&#x60;). | [optional] [default to False]
**title** | **str** | Title override. Default - the title jpdb reports. | [optional] 

## Example

```python
from kotobaya_client.models.jpdb_deck_import_request import JpdbDeckImportRequest

# TODO update the JSON string below
json = "{}"
# create an instance of JpdbDeckImportRequest from a JSON string
jpdb_deck_import_request_instance = JpdbDeckImportRequest.from_json(json)
# print the JSON string representation of the object
print(JpdbDeckImportRequest.to_json())

# convert the object into a dict
jpdb_deck_import_request_dict = jpdb_deck_import_request_instance.to_dict()
# create an instance of JpdbDeckImportRequest from a dict
jpdb_deck_import_request_from_dict = JpdbDeckImportRequest.from_dict(jpdb_deck_import_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


