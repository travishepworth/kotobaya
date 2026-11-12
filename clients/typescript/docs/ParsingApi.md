# ParsingApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**deleteParseOverride**](ParsingApi.md#deleteparseoverride) | **DELETE** /parse-overrides/{override_id} | Delete a tokenisation correction (permanent). |
| [**listParseOverrides**](ParsingApi.md#listparseoverrides) | **GET** /parse-overrides | List your tokenisation corrections. |
| [**parseText**](ParsingApi.md#parsetext) | **POST** /parse | Parse text - tokens, matched entries and their known status. |
| [**putParseOverride**](ParsingApi.md#putparseoverride) | **PUT** /parse-overrides/{override_id} | Create or replace a tokenisation correction. |



## deleteParseOverride

> deleteParseOverride(overrideId)

Delete a tokenisation correction (permanent).

Server emits &#x60;parse_override.deleted&#x60;. Already deleted - &#x60;204&#x60;, no op. Unknown id - &#x60;404&#x60;.

### Example

```ts
import {
  Configuration,
  ParsingApi,
} from '@kotobaya/api-client';
import type { DeleteParseOverrideRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ParsingApi(config);

  const body = {
    // string | Override id (client-generated UUIDv7).
    overrideId: 01931f40-6000-7000-8000-000000000801,
  } satisfies DeleteParseOverrideRequest;

  try {
    const data = await api.deleteParseOverride(body);
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
| **overrideId** | `string` | Override id (client-generated UUIDv7). | [Defaults to `undefined`] |

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


## listParseOverrides

> ParseOverridePage listParseOverrides(mediaId, scope, includeDeleted, limit, cursor)

List your tokenisation corrections.

Ordered by &#x60;updated_at&#x60; descending, then &#x60;id&#x60;.

### Example

```ts
import {
  Configuration,
  ParsingApi,
} from '@kotobaya/api-client';
import type { ListParseOverridesRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ParsingApi(config);

  const body = {
    // string | Only overrides scoped to this media (including sentence-scoped ones inside it). (optional)
    mediaId: 01931f40-3000-7000-8000-000000000e01,
    // 'global' | 'media' | 'sentence' | Only overrides of this scope. (optional)
    scope: global,
    // boolean | Also return tombstoned rows (`deleted_at` set). (optional)
    includeDeleted: false,
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListParseOverridesRequest;

  try {
    const data = await api.listParseOverrides(body);
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
| **mediaId** | `string` | Only overrides scoped to this media (including sentence-scoped ones inside it). | [Optional] [Defaults to `undefined`] |
| **scope** | `global`, `media`, `sentence` | Only overrides of this scope. | [Optional] [Defaults to `undefined`] [Enum: global, media, sentence] |
| **includeDeleted** | `boolean` | Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [Optional] [Defaults to `false`] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**ParseOverridePage**](ParseOverridePage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of overrides. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## parseText

> ParseResult parseText(parseRequest)

Parse text - tokens, matched entries and their known status.

Runs the same pipeline as media parsing (normalise, Sudachi, deinflect, match JMdict, apply global parse overrides) synchronously and stores nothing. The text is split into sentences; token offsets are relative to each sentence\&#39;s &#x60;text&#x60;. &#x60;entries&#x60; holds one summary (with your &#x60;status&#x60; and &#x60;note_id&#x60;) per distinct &#x60;seq&#x60; among the tokens. Text of 1 to 20,000 characters; longer is &#x60;413 payload_too_large&#x60;. If the parser (worker) is unreachable: &#x60;503 unavailable&#x60;. 

### Example

```ts
import {
  Configuration,
  ParsingApi,
} from '@kotobaya/api-client';
import type { ParseTextRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ParsingApi(config);

  const body = {
    // ParseRequest
    parseRequest: {"text":"パンを食べる。"},
  } satisfies ParseTextRequest;

  try {
    const data = await api.parseText(body);
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
| **parseRequest** | [ParseRequest](ParseRequest.md) |  | |

### Return type

[**ParseResult**](ParseResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Parse result. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## putParseOverride

> ParseOverride putParseOverride(overrideId, parseOverridePut)

Create or replace a tokenisation correction.

Server emits &#x60;parse_override.put&#x60; (whole-row last-writer-wins). The token surfaces must concatenate exactly to &#x60;match_text&#x60; (&#x60;422&#x60; otherwise). &#x60;sentence_id&#x60; requires &#x60;media_id&#x60;. Scope references are soft and not checked. Putting a deleted id is &#x60;410 gone&#x60; (deletes are permanent; use a new id). Overrides take effect at the next parse of the affected media (&#x60;POST /media/{id}/reparse&#x60;) and immediately in &#x60;POST /parse&#x60; (global ones). 

### Example

```ts
import {
  Configuration,
  ParsingApi,
} from '@kotobaya/api-client';
import type { PutParseOverrideRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ParsingApi(config);

  const body = {
    // string | Override id (client-generated UUIDv7).
    overrideId: 01931f40-6000-7000-8000-000000000801,
    // ParseOverridePut
    parseOverridePut: {"match_text":"食べかけ","media_id":"01931f40-3000-7000-8000-000000000e01","note":"split compound","tokens":[{"lemma":"食べる","seq":1358280,"surface":"食べ"},{"lemma":"かける","seq":1207610,"surface":"かけ"}]},
  } satisfies PutParseOverrideRequest;

  try {
    const data = await api.putParseOverride(body);
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
| **overrideId** | `string` | Override id (client-generated UUIDv7). | [Defaults to `undefined`] |
| **parseOverridePut** | [ParseOverridePut](ParseOverridePut.md) |  | |

### Return type

[**ParseOverride**](ParseOverride.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The override as stored. |  * Kotobaya-Seq -  <br>  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

