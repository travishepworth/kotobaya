
# Media

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **contentBlobHash** | **kotlin.String** | Snapshot archive for &#x60;content_version&#x60;. |  |
| **contentVersion** | **kotlin.Int** | Bumped by each published (re-)parse; 0 &#x3D; nothing published. |  |
| **coverBlobHash** | **kotlin.String** | Cover image blob. |  |
| **createdAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Creation time. |  |
| **deletedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Tombstone. |  |
| **id** | **kotlin.String** | Media id; also the id of its deck. |  |
| **jpdbId** | **kotlin.String** | jpdb deck id when &#x60;source&#x60; is &#x60;jpdb&#x60;. |  |
| **kind** | [**MediaKind**](MediaKind.md) |  |  |
| **language** | **kotlin.String** | BCP 47; always &#x60;ja&#x60; in v1. |  |
| **parseError** | **kotlin.String** | Error text when &#x60;parse_status&#x60; is &#x60;failed&#x60;. |  |
| **parseStatus** | [**inline**](#ParseStatus) | Server-owned parse state. |  |
| **source** | [**inline**](#Source) | How it was imported. Immutable. |  |
| **title** | **kotlin.String** | Title. |  |
| **updatedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Last change. |  |
| **segments** | [**kotlin.collections.List&lt;MediaSegment&gt;**](MediaSegment.md) | Segments in &#x60;ordinal&#x60; order. Present on &#x60;GET /media/{media_id}&#x60; and &#x60;PATCH&#x60;; omitted in lists. |  [optional] |


<a id="ParseStatus"></a>
## Enum: parse_status
| Name | Value |
| ---- | ----- |
| parseStatus | pending, parsing, parsed, failed |


<a id="Source"></a>
## Enum: source
| Name | Value |
| ---- | ----- |
| source | jpdb, file, url, paste |



