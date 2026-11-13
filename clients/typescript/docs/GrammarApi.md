# GrammarApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createGrammarPoint**](GrammarApi.md#creategrammarpoint) | **POST** /grammar | Create a grammar point. |
| [**deleteGrammarPoint**](GrammarApi.md#deletegrammarpoint) | **DELETE** /grammar/{grammar_point_id} | Delete a grammar point. |
| [**getGrammarPoint**](GrammarApi.md#getgrammarpoint) | **GET** /grammar/{grammar_point_id} | One grammar point. |
| [**listGrammarExamples**](GrammarApi.md#listgrammarexamples) | **GET** /grammar/{grammar_point_id}/examples | Sentences where the grammar point was seen (detected or highlighted by hand). |
| [**listGrammarPoints**](GrammarApi.md#listgrammarpoints) | **GET** /grammar | List or search the grammar library. |
| [**putGrammarSpan**](GrammarApi.md#putgrammarspan) | **PUT** /grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset} | Highlight a span of a sentence as an example of this grammar point (\&quot;I saw something weird\&quot;). |
| [**removeGrammarSpan**](GrammarApi.md#removegrammarspan) | **DELETE** /grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset} | Remove a manual span (reversible by putting it again). |
| [**testGrammarRules**](GrammarApi.md#testgrammarrules) | **POST** /grammar/{grammar_point_id}/rules/test | Run detection rules against your sentences without saving anything. |
| [**updateGrammarPoint**](GrammarApi.md#updategrammarpoint) | **PATCH** /grammar/{grammar_point_id} | Edit a grammar point. |



## createGrammarPoint

> GrammarPoint createGrammarPoint(grammarPointCreate)

Create a grammar point.

Server emits &#x60;grammar.created&#x60;. Only &#x60;title&#x60; is required. Each &#x60;detection_rules[].rule&#x60; must parse in the token-pattern language (&#x60;422&#x60; with the rule\&#39;s pointer otherwise). &#x60;related_ids&#x60; are soft references and are not checked. Creating the point does not create a grammar note or card: do that with &#x60;POST /notes&#x60; (&#x60;grammar_point_id&#x60;). Non-empty &#x60;detection_rules&#x60; make the server enqueue re-detection of all media (a &#x60;grammar-detect&#x60; job, C-13; see &#x60;Job&#x60;); media parse and import jobs also detect. Deleting a point with non-empty rules enqueues it too. 

### Example

```ts
import {
  Configuration,
  GrammarApi,
} from '@kotobaya/api-client';
import type { CreateGrammarPointRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new GrammarApi(config);

  const body = {
    // GrammarPointCreate
    grammarPointCreate: {"detection_rules":[{"draft":false,"rule":"[verb:未然形] ざる を 得 ない"}],"id":"01931f40-4000-7000-8000-000000000901","jlpt":2,"meaning":"cannot help but; have no choice but to","pattern":"〜ざるを得ない","title":"ざるを得ない"},
  } satisfies CreateGrammarPointRequest;

  try {
    const data = await api.createGrammarPoint(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **grammarPointCreate** | [GrammarPointCreate](GrammarPointCreate.md) |  | |

### Return type

[**GrammarPoint**](GrammarPoint.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | A grammar point with this &#x60;id&#x60; already existed; returned unchanged. |  -  |
| **201** | Created. |  * Kotobaya-Seq -  <br>  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## deleteGrammarPoint

> deleteGrammarPoint(grammarPointId)

Delete a grammar point.

Server emits &#x60;grammar.deleted&#x60;. Its grammar note (if any) is not deleted and keeps working from its own fields; spans stay and are hidden by readers. Already deleted: &#x60;204&#x60;, no op. 

### Example

```ts
import {
  Configuration,
  GrammarApi,
} from '@kotobaya/api-client';
import type { DeleteGrammarPointRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new GrammarApi(config);

  const body = {
    // string | Grammar point id.
    grammarPointId: 01931f40-4000-7000-8000-000000000901,
  } satisfies DeleteGrammarPointRequest;

  try {
    const data = await api.deleteGrammarPoint(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **grammarPointId** | `string` | Grammar point id. | [Defaults to `undefined`] |

### Return type

`void` (Empty response body)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Deleted. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getGrammarPoint

> GrammarPoint getGrammarPoint(grammarPointId, includeDeleted)

One grammar point.

### Example

```ts
import {
  Configuration,
  GrammarApi,
} from '@kotobaya/api-client';
import type { GetGrammarPointRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new GrammarApi(config);

  const body = {
    // string | Grammar point id.
    grammarPointId: 01931f40-4000-7000-8000-000000000901,
    // boolean | Also return tombstoned rows (`deleted_at` set). (optional)
    includeDeleted: false,
  } satisfies GetGrammarPointRequest;

  try {
    const data = await api.getGrammarPoint(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **grammarPointId** | `string` | Grammar point id. | [Defaults to `undefined`] |
| **includeDeleted** | `boolean` | Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [Optional] [Defaults to `false`] |

### Return type

[**GrammarPoint**](GrammarPoint.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The grammar point. |  -  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listGrammarExamples

> SentencePage listGrammarExamples(grammarPointId, origin, filter, limit, cursor)

Sentences where the grammar point was seen (detected or highlighted by hand).

Sentences having at least one live span for this point. Spans whose sentence no longer exists are skipped. Ordered: manual spans first, then &#x60;i+0&#x60; sentences (no unknown words), then by unknown-word count ascending, then sentence id. Each sentence\&#39;s &#x60;grammar_spans&#x60; lists all its spans; those of this point are the examples. 

### Example

```ts
import {
  Configuration,
  GrammarApi,
} from '@kotobaya/api-client';
import type { ListGrammarExamplesRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new GrammarApi(config);

  const body = {
    // string | Grammar point id.
    grammarPointId: 01931f40-4000-7000-8000-000000000901,
    // SpanOrigin | Only sentences having a span of this origin for the point. (optional)
    origin: manual,
    // 'all' | 'i+0' | 'i+1' | `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence\'s tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`.  (optional)
    filter: i+1,
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListGrammarExamplesRequest;

  try {
    const data = await api.listGrammarExamples(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **grammarPointId** | `string` | Grammar point id. | [Defaults to `undefined`] |
| **origin** | `SpanOrigin` | Only sentences having a span of this origin for the point. | [Optional] [Defaults to `undefined`] [Enum: detected, manual] |
| **filter** | `all`, `i+0`, `i+1` | &#x60;all&#x60; (default); &#x60;i+1&#x60; &#x3D; sentences with exactly one unknown word; &#x60;i+0&#x60; &#x3D; none. \&quot;Unknown word\&quot; &#x3D; a distinct entry among the sentence\&#39;s tokens whose effective word status is &#x60;new&#x60;; tokens of class &#x60;particle&#x60;, &#x60;auxiliary&#x60;, &#x60;name&#x60;, unmapped tokens and entries with status &#x60;blacklisted&#x60;/&#x60;ignored&#x60; are not counted. Because an unencoded &#x60;+&#x60; in a query string decodes to a space, servers MUST also accept &#x60;i 1&#x60; and &#x60;i 0&#x60;.  | [Optional] [Defaults to `undefined`] [Enum: all, i+0, i+1] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**SentencePage**](SentencePage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of sentences. |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listGrammarPoints

> GrammarPointPage listGrammarPoints(q, jlpt, isStub, includeDeleted, limit, cursor)

List or search the grammar library.

Ordered by &#x60;jlpt&#x60; descending with NULLs last (easiest first), then &#x60;title&#x60;, then &#x60;id&#x60;.

### Example

```ts
import {
  Configuration,
  GrammarApi,
} from '@kotobaya/api-client';
import type { ListGrammarPointsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new GrammarApi(config);

  const body = {
    // string | Case-insensitive substring of title, pattern or meaning. (optional)
    q: ざるを得ない,
    // number | Only points of this N-level. (optional)
    jlpt: 2,
    // boolean | Only stubs (`true`) or only filled-in points (`false`). (optional)
    isStub: false,
    // boolean | Also return tombstoned rows (`deleted_at` set). (optional)
    includeDeleted: false,
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListGrammarPointsRequest;

  try {
    const data = await api.listGrammarPoints(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **q** | `string` | Case-insensitive substring of title, pattern or meaning. | [Optional] [Defaults to `undefined`] |
| **jlpt** | `number` | Only points of this N-level. | [Optional] [Defaults to `undefined`] |
| **isStub** | `boolean` | Only stubs (&#x60;true&#x60;) or only filled-in points (&#x60;false&#x60;). | [Optional] [Defaults to `undefined`] |
| **includeDeleted** | `boolean` | Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [Optional] [Defaults to `false`] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**GrammarPointPage**](GrammarPointPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of grammar points. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## putGrammarSpan

> GrammarSpan putGrammarSpan(grammarPointId, sentenceId, startOffset, endOffset, grammarSpanPut)

Highlight a span of a sentence as an example of this grammar point (\&quot;I saw something weird\&quot;).

Server emits &#x60;grammar.span_put&#x60; for the manual span &#x60;(sentence_id, grammar_point_id, start_offset, end_offset, manual)&#x60;. Putting a span that exists (live or removed) is fine: it becomes live. If the sentence exists, &#x60;end_offset&#x60; beyond its text length is &#x60;422&#x60; and &#x60;media_id&#x60; is filled in from the sentence; if it does not exist the span is stored as given with the body\&#39;s &#x60;media_id&#x60; (or null). The grammar point must be live (&#x60;410&#x60; if deleted). To create a new point from a highlight, call &#x60;POST /grammar&#x60; first, then this. 

### Example

```ts
import {
  Configuration,
  GrammarApi,
} from '@kotobaya/api-client';
import type { PutGrammarSpanRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new GrammarApi(config);

  const body = {
    // string | Grammar point id.
    grammarPointId: 01931f40-4000-7000-8000-000000000901,
    // string | Sentence the span is in. Soft reference - it need not exist on the server (it may have been purged).
    sentenceId: 01931f40-5000-7000-8000-000000000701,
    // number | UTF-16 code-unit offset into the sentence text, inclusive.
    startOffset: 3,
    // number | UTF-16 code-unit offset, exclusive. Must be greater than `start_offset` (`422`).
    endOffset: 9,
    // GrammarSpanPut (optional)
    grammarSpanPut: {},
  } satisfies PutGrammarSpanRequest;

  try {
    const data = await api.putGrammarSpan(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **grammarPointId** | `string` | Grammar point id. | [Defaults to `undefined`] |
| **sentenceId** | `string` | Sentence the span is in. Soft reference - it need not exist on the server (it may have been purged). | [Defaults to `undefined`] |
| **startOffset** | `number` | UTF-16 code-unit offset into the sentence text, inclusive. | [Defaults to `undefined`] |
| **endOffset** | `number` | UTF-16 code-unit offset, exclusive. Must be greater than &#x60;start_offset&#x60; (&#x60;422&#x60;). | [Defaults to `undefined`] |
| **grammarSpanPut** | [GrammarSpanPut](GrammarSpanPut.md) |  | [Optional] |

### Return type

[**GrammarSpan**](GrammarSpan.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The span as stored. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## removeGrammarSpan

> removeGrammarSpan(grammarPointId, sentenceId, startOffset, endOffset)

Remove a manual span (reversible by putting it again).

Server emits &#x60;grammar.span_removed&#x60;. No live manual span with this key: &#x60;204&#x60;, no op. Detected spans cannot be removed this way (they are rebuilt by parse jobs; fix the rule instead). 

### Example

```ts
import {
  Configuration,
  GrammarApi,
} from '@kotobaya/api-client';
import type { RemoveGrammarSpanRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new GrammarApi(config);

  const body = {
    // string | Grammar point id.
    grammarPointId: 01931f40-4000-7000-8000-000000000901,
    // string | Sentence the span is in. Soft reference - it need not exist on the server (it may have been purged).
    sentenceId: 01931f40-5000-7000-8000-000000000701,
    // number | UTF-16 code-unit offset into the sentence text, inclusive.
    startOffset: 3,
    // number | UTF-16 code-unit offset, exclusive. Must be greater than `start_offset` (`422`).
    endOffset: 9,
  } satisfies RemoveGrammarSpanRequest;

  try {
    const data = await api.removeGrammarSpan(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **grammarPointId** | `string` | Grammar point id. | [Defaults to `undefined`] |
| **sentenceId** | `string` | Sentence the span is in. Soft reference - it need not exist on the server (it may have been purged). | [Defaults to `undefined`] |
| **startOffset** | `number` | UTF-16 code-unit offset into the sentence text, inclusive. | [Defaults to `undefined`] |
| **endOffset** | `number` | UTF-16 code-unit offset, exclusive. Must be greater than &#x60;start_offset&#x60; (&#x60;422&#x60;). | [Defaults to `undefined`] |

### Return type

`void` (Empty response body)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Removed. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## testGrammarRules

> GrammarRuleTestResult testGrammarRules(grammarPointId, grammarRuleTestRequest)

Run detection rules against your sentences without saving anything.

Evaluates &#x60;rules&#x60; (default: the point\&#39;s stored &#x60;detection_rules&#x60;) over stored sentence tokens and returns up to &#x60;limit&#x60; matching sentences with the matched spans, scanning media newest first, sentences in order. Read-only: no spans are written, no op is emitted. A rule that does not parse is &#x60;422&#x60; with its pointer. The scan stops after &#x60;limit&#x60; matches or 200,000 sentences, whichever comes first; &#x60;scanned&#x60; and &#x60;truncated&#x60; say how far it got. 

### Example

```ts
import {
  Configuration,
  GrammarApi,
} from '@kotobaya/api-client';
import type { TestGrammarRulesRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new GrammarApi(config);

  const body = {
    // string | Grammar point id.
    grammarPointId: 01931f40-4000-7000-8000-000000000901,
    // GrammarRuleTestRequest (optional)
    grammarRuleTestRequest: {"limit":20,"media_id":"01931f40-3000-7000-8000-000000000e01","rules":[{"draft":true,"rule":"[verb:未然形] ざる を 得 ない"}]},
  } satisfies TestGrammarRulesRequest;

  try {
    const data = await api.testGrammarRules(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **grammarPointId** | `string` | Grammar point id. | [Defaults to `undefined`] |
| **grammarRuleTestRequest** | [GrammarRuleTestRequest](GrammarRuleTestRequest.md) |  | [Optional] |

### Return type

[**GrammarRuleTestResult**](GrammarRuleTestResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Matches. |  -  |
| **404** | No such resource. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateGrammarPoint

> GrammarPoint updateGrammarPoint(grammarPointId, grammarPointPatch)

Edit a grammar point.

Server emits one &#x60;grammar.updated&#x60; with the registers present: &#x60;title&#x60;, &#x60;pattern&#x60;, &#x60;meaning&#x60;, &#x60;explanation&#x60;, &#x60;notes&#x60;, &#x60;jlpt&#x60;, &#x60;detection_rules&#x60; (whole array), &#x60;related_ids&#x60; (whole array), &#x60;links&#x60; (whole array), &#x60;is_stub&#x60;. Editing any text property of a stub does not clear &#x60;is_stub&#x60; by itself; send &#x60;is_stub: false&#x60;. Empty body: &#x60;422&#x60;. A winning &#x60;detection_rules&#x60; write makes the server enqueue re-detection of all media (a &#x60;grammar-detect&#x60; job, C-13; see &#x60;Job&#x60;), in the transaction that applies the op. 

### Example

```ts
import {
  Configuration,
  GrammarApi,
} from '@kotobaya/api-client';
import type { UpdateGrammarPointRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new GrammarApi(config);

  const body = {
    // string | Grammar point id.
    grammarPointId: 01931f40-4000-7000-8000-000000000901,
    // GrammarPointPatch
    grammarPointPatch: {"explanation":"Formal. Attaches to the 未然形; する becomes せざる.","is_stub":false},
  } satisfies UpdateGrammarPointRequest;

  try {
    const data = await api.updateGrammarPoint(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **grammarPointId** | `string` | Grammar point id. | [Defaults to `undefined`] |
| **grammarPointPatch** | [GrammarPointPatch](GrammarPointPatch.md) |  | |

### Return type

[**GrammarPoint**](GrammarPoint.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The updated grammar point. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

