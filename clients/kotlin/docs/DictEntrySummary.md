
# DictEntrySummary

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **freqRank** | **kotlin.Int** | Rank in the primary frequency list, or null. |  |
| **glosses** | **kotlin.collections.List&lt;kotlin.String&gt;** | English glosses of the first sense, in order. |  |
| **headword** | **kotlin.String** | First kanji form, or first reading for kana-only entries. |  |
| **headwordReading** | **kotlin.String** | First reading. |  |
| **isCommon** | **kotlin.Boolean** | JMdict \&quot;common\&quot; marker. |  |
| **noteId** | **kotlin.String** | Your live vocab note for this entry, or null. |  |
| **pos** | **kotlin.collections.List&lt;kotlin.String&gt;** | Union of JMdict part-of-speech tags. |  |
| **retired** | **kotlin.Boolean** | The entry is no longer in JMdict. |  |
| **seq** | **kotlin.Long** | JMdict sequence number (&#x60;ref.dict_entry.seq&#x60;). |  |
| **status** | [**WordStatusValue**](WordStatusValue.md) | Your effective word status (&#x60;new&#x60; when there is no row). |  |



