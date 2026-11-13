
# SentenceToken

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **cform** | **kotlin.String** | Sudachi conjugation form verbatim (e.g. &#x60;連用形-一般&#x60;); null if the word does not inflect. |  |
| **propertyClass** | [**inline**](#PropertyClass) | Coverage filter class, derived by the table in PARSER.md section 2 (same values as &#x60;occurrence.pos_class&#x60;). |  |
| **e** | **kotlin.Int** | End offset (exclusive). |  |
| **lemma** | **kotlin.String** | Dictionary form used for matching (deinflected, from the normalised text). |  |
| **&#x60;override&#x60;** | **kotlin.Boolean** | true when a parse override produced this token. |  |
| **pos** | **kotlin.collections.List&lt;kotlin.String&gt;** | Sudachi part-of-speech levels 1-4 with &#x60;*&#x60; removed; empty only for override tokens. |  |
| **reading** | **kotlin.String** | Hiragana reading of the surface (Sudachi reading, katakana folded to hiragana); may be empty only for override tokens. |  |
| **s** | **kotlin.Int** | Start offset in the sentence text (UTF-16 code units, inclusive). |  |
| **seq** | **kotlin.Long** | Matched dictionary entry; null &#x3D; unmapped. |  |
| **surface** | **kotlin.String** | The original text &#x60;[s, e)&#x60; (never normalised). |  |


<a id="PropertyClass"></a>
## Enum: class
| Name | Value |
| ---- | ----- |
| propertyClass | content, particle, auxiliary, name, other |



