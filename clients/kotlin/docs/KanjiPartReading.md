
# KanjiPartReading

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **kana** | **kotlin.String** | The part of the word&#39;s reading this kanji takes, in hiragana; it can differ from &#x60;reading&#x60; by gemination, rendaku or absorbed okurigana (学校 がっこう - &#x60;がっ&#x60; for ガク). |  |
| **reading** | **kotlin.String** | The stored reading, character for character equal to one element of &#x60;on&#x60; (when &#x60;type&#x60; is &#x60;on&#x60;) or of &#x60;kun&#x60; (when &#x60;kun&#x60;); the element to highlight. |  |
| **type** | [**inline**](#Type) | Whether &#x60;reading&#x60; is an element of the part&#39;s &#x60;on&#x60; or of its &#x60;kun&#x60;. Clients treat an unknown value as \&quot;no used reading\&quot;. |  |


<a id="Type"></a>
## Enum: type
| Name | Value |
| ---- | ----- |
| type | on, kun |



