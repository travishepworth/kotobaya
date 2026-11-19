
# DictHeadword

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **glosses** | **kotlin.collections.List&lt;kotlin.String&gt;** | English glosses of the first sense, in order; the first element is the short meaning. Empty when the first sense has no English gloss. |  |
| **headword** | **kotlin.String** | Display form. First kanji form; the first reading for kana-only entries and for kana words (ADR-046, every kanji form tagged &#x60;rK&#x60; or &#x60;sK&#x60;; SCHEMA.md 4.5 \&quot;Headword\&quot;). |  |
| **headwordReading** | **kotlin.String** | First reading. Equal to &#x60;headword&#x60; for kana-only entries and kana words (ADR-046). |  |
| **seq** | **kotlin.Long** | JMdict sequence number (&#x60;ref.dict_entry.seq&#x60;). |  |



