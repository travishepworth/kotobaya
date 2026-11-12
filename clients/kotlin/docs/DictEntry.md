
# DictEntry

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **freqRank** | **kotlin.Int** | Rank in the primary frequency list. |  |
| **freqs** | [**kotlin.collections.List&lt;DictEntryFreqsInner&gt;**](DictEntryFreqsInner.md) | Rank per loaded frequency list, ordered by the list&#39;s priority. |  |
| **headword** | **kotlin.String** | Display form. |  |
| **headwordReading** | **kotlin.String** | First reading. |  |
| **isCommon** | **kotlin.Boolean** | JMdict \&quot;common\&quot; marker. |  |
| **kanji** | **kotlin.collections.List&lt;kotlin.String&gt;** | Distinct kanji in the entry&#39;s kanji forms, in order of first appearance. |  |
| **kanjiForms** | [**kotlin.collections.List&lt;DictEntryKanjiFormsInner&gt;**](DictEntryKanjiFormsInner.md) | Kanji forms in JMdict order; empty for kana-only entries. |  |
| **language** | **kotlin.String** | BCP 47 tag; always &#x60;ja&#x60; in v1. |  |
| **misc** | **kotlin.collections.List&lt;kotlin.String&gt;** | Union of misc tags over all senses. |  |
| **noteId** | **kotlin.String** | Your live vocab note for this entry, or null. |  |
| **pitch** | [**kotlin.collections.List&lt;DictEntryPitchInner&gt;**](DictEntryPitchInner.md) | Pitch accent per reading (Kanjium); empty when unknown. |  |
| **pos** | **kotlin.collections.List&lt;kotlin.String&gt;** | Union of part-of-speech tags over all senses. |  |
| **readings** | [**kotlin.collections.List&lt;DictEntryReadingsInner&gt;**](DictEntryReadingsInner.md) | Readings in JMdict order (at least one). |  |
| **retired** | **kotlin.Boolean** | The entry is no longer in JMdict (still resolvable, excluded from search). |  |
| **senses** | [**kotlin.collections.List&lt;DictEntrySensesInner&gt;**](DictEntrySensesInner.md) | Senses in JMdict order. |  |
| **seq** | **kotlin.Long** | JMdict sequence number (&#x60;ref.dict_entry.seq&#x60;). |  |
| **status** | [**WordStatusValue**](WordStatusValue.md) | Your effective word status. |  |



