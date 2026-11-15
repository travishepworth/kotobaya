
# KanjiExample

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **gloss** | **kotlin.String** | The English glosses of the entry&#39;s first sense joined with &#x60;\&quot;; \&quot;&#x60; (&#x60;DictEntrySummary.glosses&#x60; joined); the empty string when that sense has no English gloss. |  |
| **headword** | **kotlin.String** | The entry&#39;s headword (first kanji form); always contains the kanji. |  |
| **reading** | **kotlin.String** | The entry&#39;s first reading (&#x60;headword_reading&#x60;). |  |
| **readingType** | [**inline**](#ReadingType) | How this word reads the kanji (core &#x60;KanjiExamplePicker.classify&#x60;): &#x60;on&#x60; &#x3D; one of its on&#39;yomi (incl. rendaku and gemination, 食事 しょくじ), &#x60;kun&#x60; &#x3D; one of its kun&#39;yomi (食べる), &#x60;other&#x60; &#x3D; neither could be aligned (jukujikun, ateji, irregular: 今日 きょう). A display hint only. Clients must accept unknown values as &#x60;other&#x60;. |  |
| **seq** | **kotlin.Long** | JMdict sequence number (&#x60;ref.dict_entry.seq&#x60;). |  |


<a id="ReadingType"></a>
## Enum: reading_type
| Name | Value |
| ---- | ----- |
| readingType | on, kun, other |



