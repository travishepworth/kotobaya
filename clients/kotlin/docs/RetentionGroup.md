
# RetentionGroup

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **key** | **kotlin.String** | Group key: &#x60;&lt;base_type&gt;/&lt;card kind&gt;&#x60; (e.g. &#x60;vocab/meaning&#x60;), a study day &#x60;YYYY-MM-DD&#x60;, an ISO week &#x60;YYYY-Www&#x60;, or a month &#x60;YYYY-MM&#x60;; &#x60;all&#x60; for the overall figure. |  |
| **maturePassed** | **kotlin.Int** | Of those, passed. |  |
| **matureTotal** | **kotlin.Int** | Reviews of cards whose interval was 21 days or more. |  |
| **passed** | **kotlin.Int** | Of those, rated 2-4. |  |
| **retention** | [**java.math.BigDecimal**](java.math.BigDecimal.md) | &#x60;passed / total&#x60;; null when &#x60;total&#x60; is 0. |  |
| **total** | **kotlin.Int** | Scheduled reviews in the group. |  |
| **youngPassed** | **kotlin.Int** | Of those, passed. |  |
| **youngTotal** | **kotlin.Int** | Reviews of cards whose interval was under 21 days. |  |



