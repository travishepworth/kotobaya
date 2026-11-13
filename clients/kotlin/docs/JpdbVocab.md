
# JpdbVocab

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **entrySeq** | **kotlin.Long** | The JMdict entry it is mapped to; set iff &#x60;resolution&#x60; is &#x60;mapped&#x60;. |  |
| **firstSeenAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | When an import first reported it. |  |
| **jpdbVid** | **kotlin.Long** | jpdb vocabulary id. |  |
| **media** | [**kotlin.collections.List&lt;JpdbVocabMedia&gt;**](JpdbVocabMedia.md) | Live media containing it, by &#x60;occurrence_count&#x60; descending then &#x60;media_id&#x60;. |  |
| **occurrenceTotal** | **kotlin.Int** | Sum of &#x60;media[].occurrence_count&#x60; (live media only). |  |
| **reading** | **kotlin.String** | Reading as jpdb gives it (latest import). |  |
| **resolution** | [**JpdbVocabResolution**](JpdbVocabResolution.md) |  |  |
| **resolvedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | When the current resolution was set; null iff unresolved. |  |
| **spelling** | **kotlin.String** | Spelling as jpdb gives it (latest import). |  |



