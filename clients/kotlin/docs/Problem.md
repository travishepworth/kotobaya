
# Problem

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **code** | **kotlin.String** | Stable machine-readable error code (error table in the API description). |  |
| **requestId** | **kotlin.String** | Id of this request in the server log (also sent as the &#x60;X-Request-Id&#x60; response header). |  |
| **status** | **kotlin.Int** | The HTTP status code. |  |
| **title** | **kotlin.String** | Short, stable, human-readable summary of the code. |  |
| **type** | **kotlin.String** | &#x60;urn:kotobaya:problem:&lt;code&gt;&#x60;. |  |
| **detail** | **kotlin.String** | Human-readable explanation of this occurrence. English; not for parsing. |  [optional] |
| **errors** | [**kotlin.collections.List&lt;FieldError&gt;**](FieldError.md) | Present for &#x60;validation_failed&#x60;, &#x60;invalid_query&#x60;, &#x60;bulk_too_large&#x60; and (optionally) &#x60;invalid_request&#x60; - one element per problem. |  [optional] |
| **existingId** | **kotlin.String** | Present for &#x60;already_exists&#x60; - id of the row (or job) holding the key. |  [optional] |
| **instance** | **kotlin.String** | Request path the problem occurred on. |  [optional] |
| **requiredScope** | [**Scope**](Scope.md) | Present for &#x60;insufficient_scope&#x60;. |  [optional] |



