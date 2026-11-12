
# CoverageTokens

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **known** | **kotlin.Int** | Tokens of known entries. |  |
| **knownPct** | [**java.math.BigDecimal**](java.math.BigDecimal.md) | &#x60;known / total * 100&#x60;, rounded to 2 decimals; 0 when &#x60;total&#x60; is 0. |  |
| **learning** | **kotlin.Int** | Tokens of learning entries. |  |
| **total** | **kotlin.Int** | &#x60;known + learning + unknown + unmapped&#x60;. |  |
| **unknown** | **kotlin.Int** | Tokens of unknown entries. |  |
| **unmapped** | **kotlin.Int** | Tokens that matched no entry (0 when &#x60;unmapped&#x60; is hidden). |  |



