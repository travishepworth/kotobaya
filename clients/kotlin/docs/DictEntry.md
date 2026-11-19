
# DictEntry

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **freqRank** | **kotlin.Int** | Rank in the primary frequency list. |  |
| **freqs** | [**kotlin.collections.List&lt;DictEntryFreqsInner&gt;**](DictEntryFreqsInner.md) | Rank per loaded frequency list, ordered by the list&#39;s priority. |  |
| **headword** | **kotlin.String** | Display form (SCHEMA.md 4.5 \&quot;Headword\&quot;). First kanji form; the first reading for kana-only entries and for kana words (ADR-046, every kanji form tagged &#x60;rK&#x60; or &#x60;sK&#x60;, e.g. &#x60;シンガポール&#x60;). &#x60;kanji_forms&#x60; still lists every kanji form. |  |
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
| **deckIds** | **kotlin.collections.List&lt;kotlin.String&gt;** | C-25 (additive). Ids of your live decks (&#x60;deleted_at&#x60; null; enabled or not) that hold this entry as a live item &#x60;e:&lt;seq&gt;&#x60; (an &#x60;app.deck_item&#x60; row with &#x60;entry_seq&#x60; &#x3D; &#x60;seq&#x60; and &#x60;deleted_at&#x60; null), in draw order: &#x60;deck.priority&#x60; ascending, then deck id ascending (order of the lower-case UUID text). No duplicates; empty when none. Smart decks never appear (they have no items) and &#x60;n:&lt;note id&gt;&#x60; items are not counted (they are on &#x60;Note.deck_ids&#x60;). The same set of decks as &#x60;KanjiWord.decks&#x60;, in another order. Servers that implement C-25 send it wherever this schema is returned. Absent on older servers: clients treat absent as \&quot;unknown\&quot; (fall back to scanning &#x60;listDeckItems&#x60;), never as \&quot;in no deck\&quot;.  |  [optional] |



