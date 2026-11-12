# ExportApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**exportAll**](ExportApi.md#exportall) | **GET** /export | Full JSON dump of your data. |
| [**exportAnki**](ExportApi.md#exportanki) | **GET** /export/anki | Export notes and cards as an Anki package (.apkg). |



## exportAll

> ExportDump exportAll(include)

Full JSON dump of your data.

Streams one JSON document (schema &#x60;ExportDump&#x60;) read in a single repeatable-read snapshot. &#x60;tables&#x60; holds every row of every learning-data table plus &#x60;media&#x60; and &#x60;parse_override&#x60;, tombstones and merge losers included, each row with every column of &#x60;V1__init.sql&#x60; except generated ones (&#x60;note.search_text&#x60;): arrays as JSON arrays, &#x60;jsonb&#x60; as JSON, timestamps as RFC 3339 UTC, including &#x60;clocks&#x60;/&#x60;hlc&#x60; (so a dump can seed a replica). With &#x60;include&#x3D;media_content&#x60; the bulk tables &#x60;media_segment&#x60;, &#x60;sentence&#x60;, &#x60;occurrence&#x60; and detected &#x60;sentence_grammar_span&#x60; rows are added (can be hundreds of MB). Never included: &#x60;device&#x60;, &#x60;api_token&#x60;, &#x60;change&#x60;, &#x60;job&#x60;, &#x60;blob&#x60; rows, blob bytes, &#x60;ref.*&#x60;. Sent with &#x60;Content-Disposition: attachment; filename&#x3D;\&quot;kotobaya-export-&lt;UTC date&gt;.json\&quot;&#x60;. 

### Example

```ts
import {
  Configuration,
  ExportApi,
} from '@kotobaya/api-client';
import type { ExportAllRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ExportApi(config);

  const body = {
    // Set<'media_content'> | Optional extra sections, comma-separated. (optional)
    include: ["media_content"],
  } satisfies ExportAllRequest;

  try {
    const data = await api.exportAll(body);
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
| **include** | `media_content` | Optional extra sections, comma-separated. | [Optional] [Enum: media_content] |

### Return type

[**ExportDump**](ExportDump.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The dump. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## exportAnki

> Blob exportAnki(query)

Export notes and cards as an Anki package (.apkg).

Builds an &#x60;.apkg&#x60; (Anki 2.1 &#x60;collection.anki21&#x60; format) of live notes and cards: one Anki note type per Kotobaya note type (fields in order, one card template per card kind, rendered templates from the core), tags, referenced audio and image blobs as media files, and the review log (&#x60;revlog&#x60;; &#x60;manual&#x60;, &#x60;drill&#x60;, &#x60;cram&#x60; and undone rows are left out). Scheduling is exported as FSRS memory state plus due date. With &#x60;query&#x60;, only cards matching the card search string (and their notes) are exported; a query that does not parse is &#x60;422 invalid_query&#x60;. No match still yields a valid, empty package. Sent with &#x60;Content-Disposition: attachment; filename&#x3D;\&quot;kotobaya-&lt;UTC date&gt;.apkg\&quot;&#x60;. 

### Example

```ts
import {
  Configuration,
  ExportApi,
} from '@kotobaya/api-client';
import type { ExportAnkiRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new ExportApi(config);

  const body = {
    // string | Card search string restricting what is exported. (optional)
    query: deck:"Frieren",
  } satisfies ExportAnkiRequest;

  try {
    const data = await api.exportAnki(body);
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
| **query** | `string` | Card search string restricting what is exported. | [Optional] [Defaults to `undefined`] |

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
| **200** | The package. |  -  |
| **422** | The card search string does not parse (or another validation error; see &#x60;code&#x60;). |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

