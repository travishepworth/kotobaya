
# JpdbDeckImportRequest

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **jpdbId** | **kotlin.String** | jpdb deck identifier: the path of the deck page after &#x60;jpdb.io/&#x60; without the trailing &#x60;/vocabulary-list&#x60;, e.g. &#x60;anime/5370&#x60;, or a numeric deck id. |  |
| **deck** | [**MediaDeckOptions**](MediaDeckOptions.md) |  |  [optional] |
| **kind** | [**MediaKind**](MediaKind.md) |  |  [optional] |
| **mediaId** | **kotlin.String** | Set by the server in the job payload; clients must not send it (&#x60;422&#x60;). |  [optional] |
| **title** | **kotlin.String** | Title override. Default - the title jpdb reports. |  [optional] |



