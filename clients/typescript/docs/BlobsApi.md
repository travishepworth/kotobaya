# BlobsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getBlob**](BlobsApi.md#getblob) | **GET** /blobs/{hash} | Download a blob. |
| [**putBlob**](BlobsApi.md#putblob) | **PUT** /blobs/{hash} | Upload a blob under its SHA-256. |
| [**queryBlobs**](BlobsApi.md#queryblobs) | **POST** /blobs/query | Ask which of a set of blobs the server already has. |



## getBlob

> Blob getBlob(hash)

Download a blob.

Returns the bytes with the stored &#x60;Content-Type&#x60;, &#x60;Content-Length&#x60;, &#x60;ETag: \&quot;&lt;hash&gt;\&quot;&#x60; and &#x60;Cache-Control: private, max-age&#x3D;31536000, immutable&#x60;. Supports single-range &#x60;Range&#x60; requests (&#x60;206&#x60;) and &#x60;If-None-Match&#x60; (&#x60;304&#x60;). &#x60;HEAD&#x60; behaves the same without a body (not listed separately). The server may answer &#x60;302&#x60; to a short-lived pre-signed object-store URL; clients MUST follow redirects and MUST NOT forward the &#x60;Authorization&#x60; header to the redirect target. 

### Example

```ts
import {
  Configuration,
  BlobsApi,
} from '@kotobaya/api-client';
import type { GetBlobRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new BlobsApi(config);

  const body = {
    // string | Lowercase hex SHA-256 of the blob\'s bytes.
    hash: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08,
  } satisfies GetBlobRequest;

  try {
    const data = await api.getBlob(body);
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
| **hash** | `string` | Lowercase hex SHA-256 of the blob\&#39;s bytes. | [Defaults to `undefined`] |

### Return type

**Blob**

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/octet-stream`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The blob\&#39;s bytes. |  -  |
| **206** | The requested byte range. |  -  |
| **302** | Redirect to a pre-signed object-store URL valid for at least 60 seconds. |  * Location - Pre-signed URL. <br>  |
| **304** | Not modified (&#x60;If-None-Match&#x60; matched). |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## putBlob

> Blob putBlob(kind, hash, body)

Upload a blob under its SHA-256.

The body is the raw bytes; &#x60;Content-Type&#x60; is stored as the blob\&#39;s content type (&#x60;application/octet-stream&#x60; when the caller does not know better). The server hashes the bytes while storing; a mismatch with &#x60;{hash}&#x60; is &#x60;422 hash_mismatch&#x60; and nothing is kept. Uploading a hash that exists is a no-op returning &#x60;200&#x60; with the stored metadata (the new body is discarded, &#x60;kind&#x60; and content type are not changed). Maximum size &#x60;limits.max_blob_bytes&#x60; from &#x60;GET /meta&#x60; (&#x60;413&#x60;). &#x60;origin&#x60; is always &#x60;upload&#x60;. Direct write to the system table &#x60;blob&#x60;; no op. Needs &#x60;write&#x60; or &#x60;import&#x60;. 

### Example

```ts
import {
  Configuration,
  BlobsApi,
} from '@kotobaya/api-client';
import type { PutBlobRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new BlobsApi(config);

  const body = {
    // 'audio' | 'image' | 'upload' | What the blob is - `audio` or `image` for card and media assets, `upload` for a raw import file. (`snapshot` blobs are produced only by server jobs.)
    kind: audio,
    // string | Lowercase hex SHA-256 of the blob\'s bytes.
    hash: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08,
    // Blob
    body: (binary),
  } satisfies PutBlobRequest;

  try {
    const data = await api.putBlob(body);
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
| **kind** | `audio`, `image`, `upload` | What the blob is - &#x60;audio&#x60; or &#x60;image&#x60; for card and media assets, &#x60;upload&#x60; for a raw import file. (&#x60;snapshot&#x60; blobs are produced only by server jobs.) | [Defaults to `undefined`] [Enum: audio, image, upload] |
| **hash** | `string` | Lowercase hex SHA-256 of the blob\&#39;s bytes. | [Defaults to `undefined`] |
| **body** | `Blob` |  | |

### Return type

[**Blob**](Blob.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/octet-stream`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The blob already existed. |  -  |
| **201** | Stored. |  -  |
| **413** | The body exceeds a limit from &#x60;GET /meta&#x60;. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## queryBlobs

> BlobQueryResult queryBlobs(blobQuery)

Ask which of a set of blobs the server already has.

Lets a client skip uploads. 1 to 1000 hashes; duplicates are ignored. &#x60;present&#x60; and &#x60;missing&#x60; partition the distinct input, each sorted ascending.

### Example

```ts
import {
  Configuration,
  BlobsApi,
} from '@kotobaya/api-client';
import type { QueryBlobsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new BlobsApi(config);

  const body = {
    // BlobQuery
    blobQuery: {"hashes":["9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08","2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae"]},
  } satisfies QueryBlobsRequest;

  try {
    const data = await api.queryBlobs(body);
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
| **blobQuery** | [BlobQuery](BlobQuery.md) |  | |

### Return type

[**BlobQueryResult**](BlobQueryResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Which hashes exist. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

