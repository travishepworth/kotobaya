
# Mediacreatedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **id** | **kotlin.String** | Id of the new media. Equals the envelope&#39;s entity_id. |  |
| **kind** | [**inline**](#Kind) | Register &#39;kind&#39;. |  |
| **source** | [**inline**](#Source) | Immutable. |  |
| **title** | **kotlin.String** | Register &#39;title&#39;. |  |
| **coverBlobHash** | **kotlin.String** | Register &#39;cover_blob_hash&#39;; null &#x3D; no cover. |  [optional] |
| **jpdbId** | **kotlin.String** | jpdb deck id when source &#x3D; jpdb. Immutable. Default null. At most 200 code points (C-6: unique btree index media_live_jpdb_uq). |  [optional] |


<a id="Kind"></a>
## Enum: kind
| Name | Value |
| ---- | ----- |
| kind | anime, novel, vn, manga, web, other |


<a id="Source"></a>
## Enum: source
| Name | Value |
| ---- | ----- |
| source | jpdb, file, url, paste |



