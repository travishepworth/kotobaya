
# DictEntrySummary

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **freqRank** | **kotlin.Int** | Rank in the primary frequency list, or null. |  |
| **glosses** | **kotlin.collections.List&lt;kotlin.String&gt;** | English glosses of the first sense, in order. |  |
| **headword** | **kotlin.String** | Display form. First kanji form; the first reading for kana-only entries and for kana words (ADR-046, every kanji form tagged &#x60;rK&#x60; or &#x60;sK&#x60;, e.g. &#x60;シンガポール&#x60; for 新嘉坡; SCHEMA.md 4.5 \&quot;Headword\&quot;). The other forms stay searchable. |  |
| **headwordReading** | **kotlin.String** | First reading. |  |
| **isCommon** | **kotlin.Boolean** | JMdict \&quot;common\&quot; marker. |  |
| **noteId** | **kotlin.String** | Your live vocab note for this entry, or null. |  |
| **pos** | **kotlin.collections.List&lt;kotlin.String&gt;** | Union of JMdict part-of-speech tags. |  |
| **retired** | **kotlin.Boolean** | The entry is no longer in JMdict. |  |
| **seq** | **kotlin.Long** | JMdict sequence number (&#x60;ref.dict_entry.seq&#x60;). |  |
| **status** | [**WordStatusValue**](WordStatusValue.md) | Your effective word status (&#x60;new&#x60; when there is no row). |  |
| **deckIds** | **kotlin.collections.List&lt;kotlin.String&gt;** | C-25 (additive). Ids of your live decks (&#x60;deleted_at&#x60; null; enabled or not) that hold this entry as a live item &#x60;e:&lt;seq&gt;&#x60; (an &#x60;app.deck_item&#x60; row with &#x60;entry_seq&#x60; &#x3D; &#x60;seq&#x60; and &#x60;deleted_at&#x60; null), in draw order: &#x60;deck.priority&#x60; ascending, then deck id ascending (order of the lower-case UUID text). No duplicates; empty when none. Smart decks never appear (they have no items) and &#x60;n:&lt;note id&gt;&#x60; items are not counted (they are on &#x60;Note.deck_ids&#x60;). The same set of decks as &#x60;KanjiWord.decks&#x60;, in another order. Servers that implement C-25 send it wherever this schema is returned. Absent on older servers: clients treat absent as \&quot;unknown\&quot; (fall back to scanning &#x60;listDeckItems&#x60;), never as \&quot;in no deck\&quot;.  |  [optional] |



