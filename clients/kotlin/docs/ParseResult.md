
# ParseResult

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **propertyEntries** | [**kotlin.collections.List&lt;DictEntrySummary&gt;**](DictEntrySummary.md) | One summary per distinct mapped entry, ascending by &#x60;seq&#x60;. |  |
| **sentences** | [**kotlin.collections.List&lt;ParsedSentence&gt;**](ParsedSentence.md) | Sentences in input order. |  |
| **parserVersion** | **kotlin.String** | Optional (C-12). Opaque id of the parser build (code, Sudachi dictionary, dictionary release) that produced the tokens; compare for equality only (PARSER.md 6). |  [optional] |



