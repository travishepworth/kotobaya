
# ExportDump

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **dictReleaseVersion** | **kotlin.String** | Dictionary release the entry references belong to. |  |
| **exportedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Snapshot time. |  |
| **formatVersion** | [**inline**](#FormatVersion) | Version of this dump format. |  |
| **highWaterSeq** | **kotlin.Long** | Sync head of the snapshot. |  |
| **serverVersion** | **kotlin.String** | Server build version. |  |
| **tables** | **kotlin.collections.Map&lt;kotlin.String, kotlin.collections.List&lt;kotlin.collections.Map&lt;kotlin.String, kotlin.Any&gt;&gt;&gt;** | Rows per table, keyed by table name without schema: &#x60;note_type&#x60;, &#x60;note&#x60;, &#x60;card&#x60;, &#x60;review_log&#x60;, &#x60;word_status&#x60;, &#x60;deck&#x60;, &#x60;deck_item&#x60;, &#x60;confusable&#x60;, &#x60;grammar_point&#x60;, &#x60;sentence_grammar_span&#x60; (manual rows), &#x60;settings&#x60;, &#x60;fsrs_params&#x60;, &#x60;media&#x60;, &#x60;parse_override&#x60;; with &#x60;include&#x3D;media_content&#x60; also &#x60;media_segment&#x60;, &#x60;sentence&#x60;, &#x60;occurrence&#x60; and the detected rows of &#x60;sentence_grammar_span&#x60;. Each row is an object keyed by column name. Row order: primary key ascending. |  |


<a id="FormatVersion"></a>
## Enum: format_version
| Name | Value |
| ---- | ----- |
| formatVersion | 1 |



