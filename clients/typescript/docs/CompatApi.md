# CompatApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**ankiConnect**](CompatApi.md#ankiconnectoperation) | **POST** /ankiconnect | AnkiConnect-compatible subset for Yomitan and other mining tools. |



## ankiConnect

> AnkiConnectResponse ankiConnect(ankiConnectRequest)

AnkiConnect-compatible subset for Yomitan and other mining tools.

Speaks AnkiConnect protocol version 6: the request is &#x60;{\&quot;action\&quot;, \&quot;version\&quot;, \&quot;params\&quot;, \&quot;key\&quot;}&#x60;; the response is **always HTTP 200** with &#x60;{\&quot;result\&quot;, \&quot;error\&quot;}&#x60; where exactly one is non-null (&#x60;error&#x60; is a string). Requests with &#x60;version&#x60; below 6 or absent are answered in the same v6 shape. Protocol-level failures (unknown action, bad params, auth) are reported in &#x60;error&#x60;, not as HTTP errors; only a body that is not JSON gets &#x60;400&#x60;.  **Auth** (&#x60;x-auth: body-key&#x60;). Either the usual credentials, or - because Yomitan can only send an API key in the body - a personal access token in &#x60;key&#x60;. The empty alternative &#x60;{}&#x60; in &#x60;security&#x60; exists only so that generic clients and gateways let a request without an &#x60;Authorization&#x60; header or cookie through; it does **not** make the operation public: the server authenticates such a request by &#x60;key&#x60; (same validity and revocation rules as a bearer token, &#x60;token_hash&#x60; lookup) and requires the scope named in &#x60;x-body-key-scope&#x60;. If both a header/cookie and &#x60;key&#x60; are present, the header/cookie wins (&#x60;key&#x60; is ignored). Only the &#x60;version&#x60; action needs no credential. Without a valid credential the response is &#x60;{\&quot;result\&quot;: null, \&quot;error\&quot;: \&quot;valid api key must be provided\&quot;}&#x60;. Needs the &#x60;write&#x60; scope (also for the read-only actions, to keep one rule). Cookie-authenticated calls need the CSRF header as usual. The server answers &#x60;OPTIONS&#x60; preflights for this path only with &#x60;Access-Control-Allow-Origin: *&#x60; and allows the headers &#x60;Content-Type&#x60;; cookies are never accepted cross-origin.  **Ids.** AnkiConnect note ids are integers. &#x60;anki_id(note)&#x60; &#x3D; the first 48 bits of the note\&#39;s UUIDv7 (Unix milliseconds) × 32 + the 5 bits following the version nibble (always below 2^53). It is derived, not stored; a lookup by &#x60;anki_id&#x60; resolves to the live note with the smallest id among those sharing it.  | Action | Params | Result | Behaviour | |---|---|---|---| | &#x60;version&#x60; | - | &#x60;6&#x60; | No auth needed. | | &#x60;deckNames&#x60; | - | array of strings | Names of live &#x60;user&#x60; decks, ascending, de-duplicated. If there is none, &#x60;[\&quot;Kotobaya\&quot;]&#x60; (notes added to that name go to no deck). | | &#x60;modelNames&#x60; | - | array of strings | Names of live note types, built-ins first. | | &#x60;modelFieldNames&#x60; | &#x60;modelName&#x60; | array of strings | The field **keys** of that note type, in order. Unknown name: error &#x60;model was not found: &lt;name&gt;&#x60;. | | &#x60;canAddNotes&#x60; | &#x60;notes&#x60; (array of note objects) | array of booleans | Per note, false when &#x60;addNote&#x60; would fail as a duplicate or its model is unknown. | | &#x60;addNote&#x60; | &#x60;note&#x60; | integer &#x60;anki_id&#x60; | See below. | | &#x60;findNotes&#x60; | &#x60;query&#x60; | array of integers | Supports the forms Yomitan sends: &#x60;\&quot;&lt;field&gt;:&lt;value&gt;\&quot;&#x60; terms (exact match of that field after stripping HTML, optionally quoted, combined with spaces &#x3D; AND), &#x60;deck:\&quot;&lt;name&gt;\&quot;&#x60;, and &#x60;nid:&lt;id&gt;&#x60;; anything else matches nothing. Returns &#x60;anki_id&#x60;s of live notes, ascending. | | &#x60;storeMediaFile&#x60; | &#x60;filename&#x60;, and one of &#x60;data&#x60; (base64), &#x60;url&#x60; | the stored filename | Stores the bytes as a blob (kind by extension: audio or image; else &#x60;upload&#x60;). Returns &#x60;&lt;sha256&gt;.&lt;original extension&gt;&#x60;; a &#x60;[sound:&lt;that name&gt;]&#x60; or &#x60;&lt;img src&#x3D;\&quot;&lt;that name&gt;\&quot;&gt;&#x60; in a later &#x60;addNote&#x60; field is rewritten to &#x60;[sound:&lt;sha256&gt;]&#x60; / &#x60;&lt;img src&#x3D;\&quot;blob:&lt;sha256&gt;\&quot;&gt;&#x60;. |  **&#x60;addNote&#x60;.** &#x60;note&#x60; &#x3D; &#x60;{deckName, modelName, fields: {&lt;key&gt;: &lt;html&gt;}, tags: [..], options: {allowDuplicate}, audio/picture: [{url|data, filename, fields: [..]}]}&#x60;. The server resolves &#x60;modelName&#x60; to a note type and matches a dictionary entry with the core\&#39;s matcher from the fields &#x60;expression&#x60; and &#x60;reading&#x60; when the base type is &#x60;vocab&#x60; (no match: &#x60;entry_seq&#x60; null). It then emits &#x60;note.created&#x60; (origin &#x60;ankiconnect&#x60;), &#x60;card.created&#x60; per default kind and, when &#x60;deckName&#x60; names a live user deck, &#x60;deck.item_put&#x60; - exactly as &#x60;POST /notes&#x60;. &#x60;audio&#x60;/&#x60;picture&#x60; attachments are stored as blobs and appended to the named fields. Duplicate rule: if a live note already references the matched entry, or (no entry) a live note of the same note type has the same first-field value, the result is error &#x60;cannot create note because it is a duplicate&#x60; - unless &#x60;options.allowDuplicate&#x60; is true and there is no matched entry (a second note for the same entry is never created; with &#x60;allowDuplicate&#x60; the existing note\&#39;s &#x60;anki_id&#x60; is returned instead). 

### Example

```ts
import {
  Configuration,
  CompatApi,
} from '@kotobaya/api-client';
import type { AnkiConnectOperationRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new CompatApi(config);

  const body = {
    // AnkiConnectRequest
    ankiConnectRequest: {"action":"addNote","key":"kbp_Zx81LmQpR4tUv7WyA2cD5eF9gH3jK6nM0oPqS1bT","params":{"note":{"deckName":"Inbox","fields":{"expression":"食べる","meaning":"to eat","reading":"たべる","sentence":"パンを食べる"},"modelName":"Vocab","options":{"allowDuplicate":false},"tags":["yomitan"]}},"version":6},
  } satisfies AnkiConnectOperationRequest;

  try {
    const data = await api.ankiConnect(body);
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
| **ankiConnectRequest** | [AnkiConnectRequest](AnkiConnectRequest.md) |  | |

### Return type

[**AnkiConnectResponse**](AnkiConnectResponse.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | AnkiConnect result envelope (also for protocol-level errors). |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

