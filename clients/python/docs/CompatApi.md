# kotobaya_client.CompatApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**anki_connect**](CompatApi.md#anki_connect) | **POST** /ankiconnect | AnkiConnect-compatible subset for Yomitan and other mining tools.


# **anki_connect**
> AnkiConnectResponse anki_connect(anki_connect_request)

AnkiConnect-compatible subset for Yomitan and other mining tools.

Speaks AnkiConnect protocol version 6: the request is
`{"action", "version", "params", "key"}`; the response is **always HTTP 200** with
`{"result", "error"}` where exactly one is non-null (`error` is a string). Requests with
`version` below 6 or absent are answered in the same v6 shape. Protocol-level failures
(unknown action, bad params, auth) are reported in `error`, not as HTTP errors; only a
body that is not JSON gets `400`.

**Auth** (`x-auth: body-key`). Either the usual credentials, or - because Yomitan can only
send an API key in the body - a personal access token in `key`. The empty alternative
`{}` in `security` exists only so that generic clients and gateways let a request without
an `Authorization` header or cookie through; it does **not** make the operation public:
the server authenticates such a request by `key` (same validity and revocation rules as a
bearer token, `token_hash` lookup) and requires the scope named in `x-body-key-scope`.
If both a header/cookie and `key` are present, the header/cookie wins (`key` is ignored).
Only the `version` action needs no credential. Without a valid credential the response is
`{"result": null, "error": "valid api key must be provided"}`. Needs the `write` scope
(also for the read-only actions, to keep one rule). Cookie-authenticated calls need the
CSRF header as usual. The server answers `OPTIONS` preflights for this path only with
`Access-Control-Allow-Origin: *` and allows the headers `Content-Type`; cookies are
never accepted cross-origin.

**Ids.** AnkiConnect note ids are integers. `anki_id(note)` = the first 48 bits of the
note's UUIDv7 (Unix milliseconds) × 32 + the 5 bits following the version nibble
(always below 2^53). It is derived, not stored; a lookup by `anki_id` resolves to the
live note with the smallest id among those sharing it.

| Action | Params | Result | Behaviour |
|---|---|---|---|
| `version` | - | `6` | No auth needed. |
| `deckNames` | - | array of strings | Names of live `user` decks, ascending, de-duplicated. If there is none, `["Kotobaya"]` (notes added to that name go to no deck). |
| `modelNames` | - | array of strings | Names of live note types, built-ins first. |
| `modelFieldNames` | `modelName` | array of strings | The field **keys** of that note type, in order. Unknown name: error `model was not found: <name>`. |
| `canAddNotes` | `notes` (array of note objects) | array of booleans | Per note, false when `addNote` would fail as a duplicate or its model is unknown. |
| `addNote` | `note` | integer `anki_id` | See below. |
| `findNotes` | `query` | array of integers | Supports the forms Yomitan sends: `"<field>:<value>"` terms (exact match of that field after stripping HTML, optionally quoted, combined with spaces = AND), `deck:"<name>"`, and `nid:<id>`; anything else matches nothing. Returns `anki_id`s of live notes, ascending. |
| `storeMediaFile` | `filename`, and one of `data` (base64), `url` | the stored filename | Stores the bytes as a blob (kind by extension: audio or image; else `upload`). Returns `<sha256>.<original extension>`; a `[sound:<that name>]` or `<img src="<that name>">` in a later `addNote` field is rewritten to `[sound:<sha256>]` / `<img src="blob:<sha256>">`. |

**`addNote`.** `note` = `{deckName, modelName, fields: {<key>: <html>}, tags: [..],
options: {allowDuplicate}, audio/picture: [{url|data, filename, fields: [..]}]}`.
The server resolves `modelName` to a note type and matches a dictionary entry with the
core's matcher from the fields `expression` and `reading` when the base type is `vocab`
(no match: `entry_seq` null). It then emits `note.created` (origin `ankiconnect`),
`card.created` per default kind and, when `deckName` names a live user deck,
`deck.item_put` - exactly as `POST /notes`. `audio`/`picture` attachments are stored as
blobs and appended to the named fields. Duplicate rule: if a live note already
references the matched entry, or (no entry) a live note of the same note type has the
same first-field value, the result is error `cannot create note because it is a
duplicate` - unless `options.allowDuplicate` is true and there is no matched entry
(a second note for the same entry is never created; with `allowDuplicate` the existing
note's `anki_id` is returned instead).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.anki_connect_request import AnkiConnectRequest
from kotobaya_client.models.anki_connect_response import AnkiConnectResponse
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.CompatApi(api_client)
    anki_connect_request = {"action":"addNote","key":"kbp_Zx81LmQpR4tUv7WyA2cD5eF9gH3jK6nM0oPqS1bT","params":{"note":{"deckName":"Inbox","fields":{"expression":"食べる","meaning":"to eat","reading":"たべる","sentence":"パンを食べる"},"modelName":"Vocab","options":{"allowDuplicate":false},"tags":["yomitan"]}},"version":6} # AnkiConnectRequest | 

    try:
        # AnkiConnect-compatible subset for Yomitan and other mining tools.
        api_response = api_instance.anki_connect(anki_connect_request)
        print("The response of CompatApi->anki_connect:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling CompatApi->anki_connect: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **anki_connect_request** | [**AnkiConnectRequest**](AnkiConnectRequest.md)|  | 

### Return type

[**AnkiConnectResponse**](AnkiConnectResponse.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | AnkiConnect result envelope (also for protocol-level errors). |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

