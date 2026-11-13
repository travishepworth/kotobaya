
# JpdbDeckImportRequest

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **jpdbId** | **kotlin.String** | jpdb deck identifier: the path of the deck page after &#x60;jpdb.io/&#x60; without the trailing &#x60;/vocabulary-list&#x60;, e.g. &#x60;anime/5370&#x60;, or a numeric deck id. |  |
| **deck** | [**MediaDeckOptions**](MediaDeckOptions.md) |  |  [optional] |
| **kind** | [**MediaKind**](MediaKind.md) |  |  [optional] |
| **mediaId** | **kotlin.String** | Set by the server in the job payload; clients must not send it (&#x60;422&#x60;). |  [optional] |
| **remapOnly** | **kotlin.Boolean** | Set by the server in jobs that re-publish an existing jpdb media after a mapping correction (C-11, &#x60;contracts/db/MEDIA_PIPELINE.md&#x60; 5.4): use cached jpdb responses regardless of age, fetch only what the cache lacks; &#x60;title&#x60;, &#x60;kind&#x60;, &#x60;deck&#x60; are not used. Clients must not send it (&#x60;422&#x60;). |  [optional] |
| **title** | **kotlin.String** | Title override. Default - the title jpdb reports. |  [optional] |



