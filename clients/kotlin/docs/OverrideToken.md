
# OverrideToken

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **seq** | **kotlin.Long** | Entry to map to; null &#x3D; force \&quot;no entry\&quot;. Never re-matched by the parser. |  |
| **surface** | **kotlin.String** | Surface text. |  |
| **propertyClass** | [**inline**](#PropertyClass) | Optional (C-12). Coverage class for the resulting token, e.g. &#x60;name&#x60; to hide a name the parser missed. Default - derived from the token&#39;s POS and &#x60;seq&#x60; (PARSER.md 2). |  [optional] |
| **lemma** | **kotlin.String** | Dictionary form. Default - the lemma of a parser token with exactly this span, else the surface (PARSER.md 4.4). |  [optional] |
| **reading** | **kotlin.String** | Optional (C-12). Hiragana reading for the resulting token. Default - derived (PARSER.md 4.4). |  [optional] |


<a id="PropertyClass"></a>
## Enum: class
| Name | Value |
| ---- | ----- |
| propertyClass | content, particle, auxiliary, name, other |



