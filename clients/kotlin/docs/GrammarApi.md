# GrammarApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**createGrammarPoint**](GrammarApi.md#createGrammarPoint) | **POST** /grammar | Create a grammar point. |
| [**deleteGrammarPoint**](GrammarApi.md#deleteGrammarPoint) | **DELETE** /grammar/{grammar_point_id} | Delete a grammar point. |
| [**getGrammarPoint**](GrammarApi.md#getGrammarPoint) | **GET** /grammar/{grammar_point_id} | One grammar point. |
| [**listGrammarExamples**](GrammarApi.md#listGrammarExamples) | **GET** /grammar/{grammar_point_id}/examples | Sentences where the grammar point was seen (detected or highlighted by hand). |
| [**listGrammarPoints**](GrammarApi.md#listGrammarPoints) | **GET** /grammar | List or search the grammar library. |
| [**putGrammarSpan**](GrammarApi.md#putGrammarSpan) | **PUT** /grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset} | Highlight a span of a sentence as an example of this grammar point (\&quot;I saw something weird\&quot;). |
| [**removeGrammarSpan**](GrammarApi.md#removeGrammarSpan) | **DELETE** /grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset} | Remove a manual span (reversible by putting it again). |
| [**testGrammarRules**](GrammarApi.md#testGrammarRules) | **POST** /grammar/{grammar_point_id}/rules/test | Run detection rules against your sentences without saving anything. |
| [**updateGrammarPoint**](GrammarApi.md#updateGrammarPoint) | **PATCH** /grammar/{grammar_point_id} | Edit a grammar point. |


<a id="createGrammarPoint"></a>
# **createGrammarPoint**
> GrammarPoint createGrammarPoint(grammarPointCreate)

Create a grammar point.

Server emits &#x60;grammar.created&#x60;. Only &#x60;title&#x60; is required. Each &#x60;detection_rules[].rule&#x60; must parse in the token-pattern language (&#x60;422&#x60; with the rule&#39;s pointer otherwise). &#x60;related_ids&#x60; are soft references and are not checked. Creating the point does not create a grammar note or card: do that with &#x60;POST /notes&#x60; (&#x60;grammar_point_id&#x60;). Non-empty &#x60;detection_rules&#x60; make the server enqueue re-detection of all media (a &#x60;grammar-detect&#x60; job, C-13; see &#x60;Job&#x60;); media parse and import jobs also detect. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = GrammarApi()
val grammarPointCreate : GrammarPointCreate = {"detection_rules":[{"draft":false,"rule":"[verb:未然形] ざる を 得 ない"}],"id":"01931f40-4000-7000-8000-000000000901","jlpt":2,"meaning":"cannot help but; have no choice but to","pattern":"〜ざるを得ない","title":"ざるを得ない"} // GrammarPointCreate | 
try {
    val result : GrammarPoint = apiInstance.createGrammarPoint(grammarPointCreate)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling GrammarApi#createGrammarPoint")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling GrammarApi#createGrammarPoint")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **grammarPointCreate** | [**GrammarPointCreate**](GrammarPointCreate.md)|  | |

### Return type

[**GrammarPoint**](GrammarPoint.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

<a id="deleteGrammarPoint"></a>
# **deleteGrammarPoint**
> deleteGrammarPoint(grammarPointId)

Delete a grammar point.

Server emits &#x60;grammar.deleted&#x60;. Its grammar note (if any) is not deleted and keeps working from its own fields; spans stay and are hidden by readers. Already deleted: &#x60;204&#x60;, no op. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = GrammarApi()
val grammarPointId : kotlin.String = 01931f40-4000-7000-8000-000000000901 // kotlin.String | Grammar point id.
try {
    apiInstance.deleteGrammarPoint(grammarPointId)
} catch (e: ClientException) {
    println("4xx response calling GrammarApi#deleteGrammarPoint")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling GrammarApi#deleteGrammarPoint")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **grammarPointId** | **kotlin.String**| Grammar point id. | |

### Return type

null (empty response body)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/problem+json

<a id="getGrammarPoint"></a>
# **getGrammarPoint**
> GrammarPoint getGrammarPoint(grammarPointId, includeDeleted)

One grammar point.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = GrammarApi()
val grammarPointId : kotlin.String = 01931f40-4000-7000-8000-000000000901 // kotlin.String | Grammar point id.
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
try {
    val result : GrammarPoint = apiInstance.getGrammarPoint(grammarPointId, includeDeleted)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling GrammarApi#getGrammarPoint")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling GrammarApi#getGrammarPoint")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **grammarPointId** | **kotlin.String**| Grammar point id. | |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |

### Return type

[**GrammarPoint**](GrammarPoint.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="listGrammarExamples"></a>
# **listGrammarExamples**
> SentencePage listGrammarExamples(grammarPointId, origin, filter, limit, cursor)

Sentences where the grammar point was seen (detected or highlighted by hand).

Sentences having at least one live span for this point. Spans whose sentence no longer exists are skipped. Ordered: manual spans first, then &#x60;i+0&#x60; sentences (no unknown words), then by unknown-word count ascending, then sentence id. Each sentence&#39;s &#x60;grammar_spans&#x60; lists all its spans; those of this point are the examples. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = GrammarApi()
val grammarPointId : kotlin.String = 01931f40-4000-7000-8000-000000000901 // kotlin.String | Grammar point id.
val origin : SpanOrigin = manual // SpanOrigin | Only sentences having a span of this origin for the point.
val filter : kotlin.String = i+1 // kotlin.String | `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. 
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : SentencePage = apiInstance.listGrammarExamples(grammarPointId, origin, filter, limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling GrammarApi#listGrammarExamples")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling GrammarApi#listGrammarExamples")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **grammarPointId** | **kotlin.String**| Grammar point id. | |
| **origin** | [**SpanOrigin**](.md)| Only sentences having a span of this origin for the point. | [optional] [enum: detected, manual] |
| **filter** | **kotlin.String**| &#x60;all&#x60; (default); &#x60;i+1&#x60; &#x3D; sentences with exactly one unknown word; &#x60;i+0&#x60; &#x3D; none. \&quot;Unknown word\&quot; &#x3D; a distinct entry among the sentence&#39;s tokens whose effective word status is &#x60;new&#x60;; tokens of class &#x60;particle&#x60;, &#x60;auxiliary&#x60;, &#x60;name&#x60;, unmapped tokens and entries with status &#x60;blacklisted&#x60;/&#x60;ignored&#x60; are not counted. Because an unencoded &#x60;+&#x60; in a query string decodes to a space, servers MUST also accept &#x60;i 1&#x60; and &#x60;i 0&#x60;.  | [optional] [enum: all, i+0, i+1] |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**SentencePage**](SentencePage.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="listGrammarPoints"></a>
# **listGrammarPoints**
> GrammarPointPage listGrammarPoints(q, jlpt, isStub, includeDeleted, limit, cursor)

List or search the grammar library.

Ordered by &#x60;jlpt&#x60; descending with NULLs last (easiest first), then &#x60;title&#x60;, then &#x60;id&#x60;.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = GrammarApi()
val q : kotlin.String = ざるを得ない // kotlin.String | Case-insensitive substring of title, pattern or meaning.
val jlpt : kotlin.Int = 2 // kotlin.Int | Only points of this N-level.
val isStub : kotlin.Boolean = false // kotlin.Boolean | Only stubs (`true`) or only filled-in points (`false`).
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : GrammarPointPage = apiInstance.listGrammarPoints(q, jlpt, isStub, includeDeleted, limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling GrammarApi#listGrammarPoints")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling GrammarApi#listGrammarPoints")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **q** | **kotlin.String**| Case-insensitive substring of title, pattern or meaning. | [optional] |
| **jlpt** | **kotlin.Int**| Only points of this N-level. | [optional] |
| **isStub** | **kotlin.Boolean**| Only stubs (&#x60;true&#x60;) or only filled-in points (&#x60;false&#x60;). | [optional] |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**GrammarPointPage**](GrammarPointPage.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="putGrammarSpan"></a>
# **putGrammarSpan**
> GrammarSpan putGrammarSpan(grammarPointId, sentenceId, startOffset, endOffset, grammarSpanPut)

Highlight a span of a sentence as an example of this grammar point (\&quot;I saw something weird\&quot;).

Server emits &#x60;grammar.span_put&#x60; for the manual span &#x60;(sentence_id, grammar_point_id, start_offset, end_offset, manual)&#x60;. Putting a span that exists (live or removed) is fine: it becomes live. If the sentence exists, &#x60;end_offset&#x60; beyond its text length is &#x60;422&#x60; and &#x60;media_id&#x60; is filled in from the sentence; if it does not exist the span is stored as given with the body&#39;s &#x60;media_id&#x60; (or null). The grammar point must be live (&#x60;410&#x60; if deleted). To create a new point from a highlight, call &#x60;POST /grammar&#x60; first, then this. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = GrammarApi()
val grammarPointId : kotlin.String = 01931f40-4000-7000-8000-000000000901 // kotlin.String | Grammar point id.
val sentenceId : kotlin.String = 01931f40-5000-7000-8000-000000000701 // kotlin.String | Sentence the span is in. Soft reference - it need not exist on the server (it may have been purged).
val startOffset : kotlin.Int = 3 // kotlin.Int | UTF-16 code-unit offset into the sentence text, inclusive.
val endOffset : kotlin.Int = 9 // kotlin.Int | UTF-16 code-unit offset, exclusive. Must be greater than `start_offset` (`422`).
val grammarSpanPut : GrammarSpanPut = {} // GrammarSpanPut | 
try {
    val result : GrammarSpan = apiInstance.putGrammarSpan(grammarPointId, sentenceId, startOffset, endOffset, grammarSpanPut)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling GrammarApi#putGrammarSpan")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling GrammarApi#putGrammarSpan")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **grammarPointId** | **kotlin.String**| Grammar point id. | |
| **sentenceId** | **kotlin.String**| Sentence the span is in. Soft reference - it need not exist on the server (it may have been purged). | |
| **startOffset** | **kotlin.Int**| UTF-16 code-unit offset into the sentence text, inclusive. | |
| **endOffset** | **kotlin.Int**| UTF-16 code-unit offset, exclusive. Must be greater than &#x60;start_offset&#x60; (&#x60;422&#x60;). | |
| **grammarSpanPut** | [**GrammarSpanPut**](GrammarSpanPut.md)|  | [optional] |

### Return type

[**GrammarSpan**](GrammarSpan.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

<a id="removeGrammarSpan"></a>
# **removeGrammarSpan**
> removeGrammarSpan(grammarPointId, sentenceId, startOffset, endOffset)

Remove a manual span (reversible by putting it again).

Server emits &#x60;grammar.span_removed&#x60;. No live manual span with this key: &#x60;204&#x60;, no op. Detected spans cannot be removed this way (they are rebuilt by parse jobs; fix the rule instead). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = GrammarApi()
val grammarPointId : kotlin.String = 01931f40-4000-7000-8000-000000000901 // kotlin.String | Grammar point id.
val sentenceId : kotlin.String = 01931f40-5000-7000-8000-000000000701 // kotlin.String | Sentence the span is in. Soft reference - it need not exist on the server (it may have been purged).
val startOffset : kotlin.Int = 3 // kotlin.Int | UTF-16 code-unit offset into the sentence text, inclusive.
val endOffset : kotlin.Int = 9 // kotlin.Int | UTF-16 code-unit offset, exclusive. Must be greater than `start_offset` (`422`).
try {
    apiInstance.removeGrammarSpan(grammarPointId, sentenceId, startOffset, endOffset)
} catch (e: ClientException) {
    println("4xx response calling GrammarApi#removeGrammarSpan")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling GrammarApi#removeGrammarSpan")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **grammarPointId** | **kotlin.String**| Grammar point id. | |
| **sentenceId** | **kotlin.String**| Sentence the span is in. Soft reference - it need not exist on the server (it may have been purged). | |
| **startOffset** | **kotlin.Int**| UTF-16 code-unit offset into the sentence text, inclusive. | |
| **endOffset** | **kotlin.Int**| UTF-16 code-unit offset, exclusive. Must be greater than &#x60;start_offset&#x60; (&#x60;422&#x60;). | |

### Return type

null (empty response body)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/problem+json

<a id="testGrammarRules"></a>
# **testGrammarRules**
> GrammarRuleTestResult testGrammarRules(grammarPointId, grammarRuleTestRequest)

Run detection rules against your sentences without saving anything.

Evaluates &#x60;rules&#x60; (default: the point&#39;s stored &#x60;detection_rules&#x60;) over stored sentence tokens and returns up to &#x60;limit&#x60; matching sentences with the matched spans, scanning media newest first, sentences in order. Read-only: no spans are written, no op is emitted. A rule that does not parse is &#x60;422&#x60; with its pointer. The scan stops after &#x60;limit&#x60; matches or 200,000 sentences, whichever comes first; &#x60;scanned&#x60; and &#x60;truncated&#x60; say how far it got. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = GrammarApi()
val grammarPointId : kotlin.String = 01931f40-4000-7000-8000-000000000901 // kotlin.String | Grammar point id.
val grammarRuleTestRequest : GrammarRuleTestRequest = {"limit":20,"media_id":"01931f40-3000-7000-8000-000000000e01","rules":[{"draft":true,"rule":"[verb:未然形] ざる を 得 ない"}]} // GrammarRuleTestRequest | 
try {
    val result : GrammarRuleTestResult = apiInstance.testGrammarRules(grammarPointId, grammarRuleTestRequest)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling GrammarApi#testGrammarRules")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling GrammarApi#testGrammarRules")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **grammarPointId** | **kotlin.String**| Grammar point id. | |
| **grammarRuleTestRequest** | [**GrammarRuleTestRequest**](GrammarRuleTestRequest.md)|  | [optional] |

### Return type

[**GrammarRuleTestResult**](GrammarRuleTestResult.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

<a id="updateGrammarPoint"></a>
# **updateGrammarPoint**
> GrammarPoint updateGrammarPoint(grammarPointId, grammarPointPatch)

Edit a grammar point.

Server emits one &#x60;grammar.updated&#x60; with the registers present: &#x60;title&#x60;, &#x60;pattern&#x60;, &#x60;meaning&#x60;, &#x60;explanation&#x60;, &#x60;notes&#x60;, &#x60;jlpt&#x60;, &#x60;detection_rules&#x60; (whole array), &#x60;related_ids&#x60; (whole array), &#x60;links&#x60; (whole array), &#x60;is_stub&#x60;. Editing any text property of a stub does not clear &#x60;is_stub&#x60; by itself; send &#x60;is_stub: false&#x60;. Empty body: &#x60;422&#x60;. A winning &#x60;detection_rules&#x60; write makes the server enqueue re-detection of all media (a &#x60;grammar-detect&#x60; job, C-13; see &#x60;Job&#x60;), in the transaction that applies the op. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = GrammarApi()
val grammarPointId : kotlin.String = 01931f40-4000-7000-8000-000000000901 // kotlin.String | Grammar point id.
val grammarPointPatch : GrammarPointPatch = {"explanation":"Formal. Attaches to the 未然形; する becomes せざる.","is_stub":false} // GrammarPointPatch | 
try {
    val result : GrammarPoint = apiInstance.updateGrammarPoint(grammarPointId, grammarPointPatch)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling GrammarApi#updateGrammarPoint")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling GrammarApi#updateGrammarPoint")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **grammarPointId** | **kotlin.String**| Grammar point id. | |
| **grammarPointPatch** | [**GrammarPointPatch**](GrammarPointPatch.md)|  | |

### Return type

[**GrammarPoint**](GrammarPoint.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

