# TokensApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createToken**](TokensApi.md#createtoken) | **POST** /tokens | Create a personal access token. |
| [**getToken**](TokensApi.md#gettoken) | **GET** /tokens/{token_id} | One credential. |
| [**listTokens**](TokensApi.md#listtokens) | **GET** /tokens | List credentials of every kind (never the token strings or hashes). |
| [**revokeToken**](TokensApi.md#revoketoken) | **DELETE** /tokens/{token_id} | Revoke a credential of any kind. |



## createToken

> TokenCreated createToken(tokenCreate)

Create a personal access token.

Returns the token string once. &#x60;scopes&#x60; must be non-empty, without duplicates. &#x60;expires_at&#x60;, when given, must be in the future. With &#x60;with_device: true&#x60; the server also creates a &#x60;device&#x60; row of kind &#x60;script&#x60; (server-generated id, same name) and binds the token to it, which allows the token to use &#x60;POST /sync/push&#x60;; revoking the token does not revoke that device, revoking the device revokes the token. Names need not be unique. 

### Example

```ts
import {
  Configuration,
  TokensApi,
} from '@kotobaya/api-client';
import type { CreateTokenRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new TokensApi(config);

  const body = {
    // TokenCreate
    tokenCreate: {"name":"nightly-import","scopes":["read","import"]},
  } satisfies CreateTokenRequest;

  try {
    const data = await api.createToken(body);
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
| **tokenCreate** | [TokenCreate](TokenCreate.md) |  | |

### Return type

[**TokenCreated**](TokenCreated.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | Created. &#x60;token&#x60; is shown only now. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getToken

> Token getToken(tokenId)

One credential.

### Example

```ts
import {
  Configuration,
  TokensApi,
} from '@kotobaya/api-client';
import type { GetTokenRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new TokensApi(config);

  const body = {
    // string | `api_token.id`.
    tokenId: 01931f3f-0000-7000-8000-0000000000b1,
  } satisfies GetTokenRequest;

  try {
    const data = await api.getToken(body);
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
| **tokenId** | `string` | &#x60;api_token.id&#x60;. | [Defaults to `undefined`] |

### Return type

[**Token**](Token.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The token (metadata only). |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listTokens

> TokenList listTokens(kind, active)

List credentials of every kind (never the token strings or hashes).

Not paginated. Ordered by &#x60;created_at&#x60; descending, then &#x60;id&#x60;. Includes expired and revoked rows; filter with &#x60;kind&#x60; and &#x60;active&#x60;. 

### Example

```ts
import {
  Configuration,
  TokensApi,
} from '@kotobaya/api-client';
import type { ListTokensRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new TokensApi(config);

  const body = {
    // TokenKind | Only tokens of this kind. (optional)
    kind: personal,
    // boolean | `true` = only currently valid tokens; `false` = only expired or revoked; absent = all. (optional)
    active: true,
  } satisfies ListTokensRequest;

  try {
    const data = await api.listTokens(body);
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
| **kind** | `TokenKind` | Only tokens of this kind. | [Optional] [Defaults to `undefined`] [Enum: session, device, personal] |
| **active** | `boolean` | &#x60;true&#x60; &#x3D; only currently valid tokens; &#x60;false&#x60; &#x3D; only expired or revoked; absent &#x3D; all. | [Optional] [Defaults to `undefined`] |

### Return type

[**TokenList**](TokenList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Tokens. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## revokeToken

> revokeToken(tokenId)

Revoke a credential of any kind.

Sets &#x60;revoked_at&#x60;. Idempotent (&#x60;204&#x60; if already revoked or expired). Rows are kept for the audit trail. A caller may revoke the token it is using; the response is still &#x60;204&#x60; and the next request fails with &#x60;401&#x60;. 

### Example

```ts
import {
  Configuration,
  TokensApi,
} from '@kotobaya/api-client';
import type { RevokeTokenRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new TokensApi(config);

  const body = {
    // string | `api_token.id`.
    tokenId: 01931f3f-0000-7000-8000-0000000000b1,
  } satisfies RevokeTokenRequest;

  try {
    const data = await api.revokeToken(body);
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
| **tokenId** | `string` | &#x60;api_token.id&#x60;. | [Defaults to `undefined`] |

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
| **204** | Revoked. |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

