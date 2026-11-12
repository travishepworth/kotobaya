# BlobsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**getBlob**](BlobsApi.md#getBlob) | **GET** /blobs/{hash} | Download a blob. |
| [**putBlob**](BlobsApi.md#putBlob) | **PUT** /blobs/{hash} | Upload a blob under its SHA-256. |
| [**queryBlobs**](BlobsApi.md#queryBlobs) | **POST** /blobs/query | Ask which of a set of blobs the server already has. |


<a id="getBlob"></a>
# **getBlob**
> java.io.File getBlob(hash)

Download a blob.

Returns the bytes with the stored &#x60;Content-Type&#x60;, &#x60;Content-Length&#x60;, &#x60;ETag: \&quot;&lt;hash&gt;\&quot;&#x60; and &#x60;Cache-Control: private, max-age&#x3D;31536000, immutable&#x60;. Supports single-range &#x60;Range&#x60; requests (&#x60;206&#x60;) and &#x60;If-None-Match&#x60; (&#x60;304&#x60;). &#x60;HEAD&#x60; behaves the same without a body (not listed separately). The server may answer &#x60;302&#x60; to a short-lived pre-signed object-store URL; clients MUST follow redirects and MUST NOT forward the &#x60;Authorization&#x60; header to the redirect target. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = BlobsApi()
val hash : kotlin.String = 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08 // kotlin.String | Lowercase hex SHA-256 of the blob's bytes.
try {
    val result : java.io.File = apiInstance.getBlob(hash)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling BlobsApi#getBlob")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling BlobsApi#getBlob")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **hash** | **kotlin.String**| Lowercase hex SHA-256 of the blob&#39;s bytes. | |

### Return type

[**java.io.File**](java.io.File.md)

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
 - **Accept**: application/octet-stream, application/problem+json

<a id="putBlob"></a>
# **putBlob**
> Blob putBlob(hash, kind, body)

Upload a blob under its SHA-256.

The body is the raw bytes; &#x60;Content-Type&#x60; is stored as the blob&#39;s content type (&#x60;application/octet-stream&#x60; when the caller does not know better). The server hashes the bytes while storing; a mismatch with &#x60;{hash}&#x60; is &#x60;422 hash_mismatch&#x60; and nothing is kept. Uploading a hash that exists is a no-op returning &#x60;200&#x60; with the stored metadata (the new body is discarded, &#x60;kind&#x60; and content type are not changed). Maximum size &#x60;limits.max_blob_bytes&#x60; from &#x60;GET /meta&#x60; (&#x60;413&#x60;). &#x60;origin&#x60; is always &#x60;upload&#x60;. Direct write to the system table &#x60;blob&#x60;; no op. Needs &#x60;write&#x60; or &#x60;import&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = BlobsApi()
val hash : kotlin.String = 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08 // kotlin.String | Lowercase hex SHA-256 of the blob's bytes.
val kind : kotlin.String = audio // kotlin.String | What the blob is - `audio` or `image` for card and media assets, `upload` for a raw import file. (`snapshot` blobs are produced only by server jobs.)
val body : java.io.File = (binary) // java.io.File | 
try {
    val result : Blob = apiInstance.putBlob(hash, kind, body)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling BlobsApi#putBlob")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling BlobsApi#putBlob")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **hash** | **kotlin.String**| Lowercase hex SHA-256 of the blob&#39;s bytes. | |
| **kind** | **kotlin.String**| What the blob is - &#x60;audio&#x60; or &#x60;image&#x60; for card and media assets, &#x60;upload&#x60; for a raw import file. (&#x60;snapshot&#x60; blobs are produced only by server jobs.) | [enum: audio, image, upload] |
| **body** | **java.io.File**|  | |

### Return type

[**Blob**](Blob.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/octet-stream
 - **Accept**: application/json, application/problem+json

<a id="queryBlobs"></a>
# **queryBlobs**
> BlobQueryResult queryBlobs(blobQuery)

Ask which of a set of blobs the server already has.

Lets a client skip uploads. 1 to 1000 hashes; duplicates are ignored. &#x60;present&#x60; and &#x60;missing&#x60; partition the distinct input, each sorted ascending.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = BlobsApi()
val blobQuery : BlobQuery = {"hashes":["9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08","2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae"]} // BlobQuery | 
try {
    val result : BlobQueryResult = apiInstance.queryBlobs(blobQuery)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling BlobsApi#queryBlobs")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling BlobsApi#queryBlobs")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **blobQuery** | [**BlobQuery**](BlobQuery.md)|  | |

### Return type

[**BlobQueryResult**](BlobQueryResult.md)

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

