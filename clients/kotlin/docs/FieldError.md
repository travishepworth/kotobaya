
# FieldError

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **code** | **kotlin.String** | One of &#x60;required&#x60;, &#x60;unknown_field&#x60;, &#x60;invalid_type&#x60;, &#x60;invalid_value&#x60;, &#x60;out_of_range&#x60;, &#x60;too_long&#x60;, &#x60;not_found&#x60; (referenced row missing), &#x60;immutable&#x60;, &#x60;conflicting_fields&#x60;, or a search-parser code such as &#x60;unknown_term&#x60;. |  |
| **detail** | **kotlin.String** | Human-readable explanation. |  |
| **pointer** | **kotlin.String** | JSON Pointer into the request body (&#x60;/fields/meaning&#x60;), or &#x60;/&lt;name&gt;&#x60; for a query or path parameter. |  |
| **position** | **kotlin.Int** | For search-string errors - UTF-16 offset of the error in the string. |  [optional] |



