
# SentenceToken

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **e** | **kotlin.Int** | End offset (exclusive). |  |
| **s** | **kotlin.Int** | Start offset in the sentence text (UTF-16 code units, inclusive). |  |
| **seq** | **kotlin.Long** | Matched dictionary entry; null &#x3D; unmapped. |  |
| **surface** | **kotlin.String** | Text of the token. |  |
| **cform** | **kotlin.String** | Conjugation form. |  [optional] |
| **propertyClass** | [**inline**](#PropertyClass) | Coverage filter class. |  [optional] |
| **lemma** | **kotlin.String** | Dictionary form. |  [optional] |
| **&#x60;override&#x60;** | **kotlin.Boolean** | true when a parse override produced this token. |  [optional] |
| **pos** | **kotlin.collections.List&lt;kotlin.String&gt;** | Sudachi part-of-speech hierarchy. |  [optional] |
| **reading** | **kotlin.String** | Kana reading of the surface. |  [optional] |


<a id="PropertyClass"></a>
## Enum: class
| Name | Value |
| ---- | ----- |
| propertyClass | content, particle, auxiliary, name, other |



