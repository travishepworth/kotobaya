
# KanjiPart

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **keyword** | **kotlin.String** | Your keyword for the kanji - the plain text of the &#x60;keyword&#x60; field of your live kanji note for this literal when it is not blank - else the first of &#x60;meanings&#x60;; null when there is neither. |  |
| **kun** | **kotlin.collections.List&lt;kotlin.String&gt;** | Kun readings as stored (&#x60;Kanji.kun_readings&#x60;, hiragana with okurigana dots and &#x60;-&#x60;). May be empty. |  |
| **literal** | **kotlin.String** | The kanji, exactly one Unicode code point (two UTF-16 units outside the BMP). |  |
| **meanings** | **kotlin.collections.List&lt;kotlin.String&gt;** | English meanings (&#x60;Kanji.meanings&#x60;, KANJIDIC2 order). Empty when the kanji is not in the dictionary. |  |
| **on** | **kotlin.collections.List&lt;kotlin.String&gt;** | On readings as stored (&#x60;Kanji.on_readings&#x60;, katakana). May be empty. |  |
| **usedReading** | [**KanjiPartReading**](KanjiPartReading.md) | The reading this word uses for the kanji (core &#x60;KanjiParts.usedReading&#x60;), or null when the word&#39;s reading cannot be aligned to one of the kanji&#39;s readings (jukujikun and ateji such as 今日 きょう, irregular readings such as 日本 にほん, a kanji without readings, an empty reading). |  |



