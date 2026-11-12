# DictionaryApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getDictEntry**](DictionaryApi.md#getdictentry) | **GET** /dict/entries/{seq} | One JMdict entry with frequency, pitch, kanji and your status. |
| [**getDictRelease**](DictionaryApi.md#getdictrelease) | **GET** /dict/release | The current dictionary release and its downloadable device builds. |
| [**getKanji**](DictionaryApi.md#getkanji) | **GET** /kanji/{literal} | One kanji from KANJIDIC2 with components and your kanji note, if any. |
| [**listEntryExamples**](DictionaryApi.md#listentryexamples) | **GET** /dict/entries/{seq}/examples | Example sentences for an entry, from your media and from Tatoeba. |
| [**refreshDictionary**](DictionaryApi.md#refreshdictionary) | **POST** /dict/refresh | Queue a dictionary refresh (download sources, build and load a new release). |
| [**searchDictionary**](DictionaryApi.md#searchdictionary) | **GET** /dict/search | Search JMdict entries by kanji, kana, romaji or English. |



## getDictEntry

> DictEntry getDictEntry(seq)

One JMdict entry with frequency, pitch, kanji and your status.

Retired entries are still returned (&#x60;retired&#x60; is true). Unknown &#x60;seq&#x60; is &#x60;404&#x60;.

### Example

```ts
import {
  Configuration,
  DictionaryApi,
} from '@kotobaya/api-client';
import type { GetDictEntryRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DictionaryApi(config);

  const body = {
    // number | JMdict sequence number of the entry.
    seq: 1358280,
  } satisfies GetDictEntryRequest;

  try {
    const data = await api.getDictEntry(body);
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
| **seq** | `number` | JMdict sequence number of the entry. | [Defaults to `undefined`] |

### Return type

[**DictEntry**](DictEntry.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The entry. |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getDictRelease

> DictRelease getDictRelease()

The current dictionary release and its downloadable device builds.

The phone compares &#x60;id&#x60; with the release of its dictionary file and, when behind, downloads an artifact through &#x60;GET /blobs/{hash}&#x60;: a &#x60;diff&#x60; whose &#x60;from_release&#x60; equals its release and whose &#x60;scope&#x60; matches, else the &#x60;full&#x60; build. &#x60;404&#x60; when no release has been loaded yet. Package and diff formats, hashes and the full install/update algorithm: &#x60;contracts/db/DICT_PACKAGE.md&#x60;. 

### Example

```ts
import {
  Configuration,
  DictionaryApi,
} from '@kotobaya/api-client';
import type { GetDictReleaseRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DictionaryApi(config);

  try {
    const data = await api.getDictRelease();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**DictRelease**](DictRelease.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Current release. |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getKanji

> Kanji getKanji(literal)

One kanji from KANJIDIC2 with components and your kanji note, if any.

More than one code point is &#x60;422&#x60;; a character not in &#x60;ref.kanji&#x60; is &#x60;404&#x60;. (&#x60;maxLength: 2&#x60; because a code point outside the BMP is two UTF-16 units.) 

### Example

```ts
import {
  Configuration,
  DictionaryApi,
} from '@kotobaya/api-client';
import type { GetKanjiRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DictionaryApi(config);

  const body = {
    // string | Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment `hot` is the separate operation `listHotKanji`.
    literal: 績,
  } satisfies GetKanjiRequest;

  try {
    const data = await api.getKanji(body);
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
| **literal** | `string` | Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment &#x60;hot&#x60; is the separate operation &#x60;listHotKanji&#x60;. | [Defaults to `undefined`] |

### Return type

[**Kanji**](Kanji.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The kanji. |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listEntryExamples

> EntryExamples listEntryExamples(seq, limit)

Example sentences for an entry, from your media and from Tatoeba.

&#x60;media&#x60;: up to &#x60;limit&#x60; sentences from imported media that contain the entry (resolved through &#x60;occurrence.sentence_ids&#x60;), sentences with the fewest unknown words first, then by media &#x60;created_at&#x60; descending, then sentence id. &#x60;tatoeba&#x60;: up to &#x60;limit&#x60; rows of &#x60;ref.example_sentence&#x60; linked to the entry, shortest text first, then id. Either list may be empty. Not paginated. Unknown &#x60;seq&#x60; is &#x60;404&#x60;. 

### Example

```ts
import {
  Configuration,
  DictionaryApi,
} from '@kotobaya/api-client';
import type { ListEntryExamplesRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DictionaryApi(config);

  const body = {
    // number | JMdict sequence number of the entry.
    seq: 1358280,
    // number | Maximum number of sentences per list. (optional)
    limit: 10,
  } satisfies ListEntryExamplesRequest;

  try {
    const data = await api.listEntryExamples(body);
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
| **seq** | `number` | JMdict sequence number of the entry. | [Defaults to `undefined`] |
| **limit** | `number` | Maximum number of sentences per list. | [Optional] [Defaults to `10`] |

### Return type

[**EntryExamples**](EntryExamples.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Example sentences. |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## refreshDictionary

> Job refreshDictionary(jobPayloadDictRefresh)

Queue a dictionary refresh (download sources, build and load a new release).

Enqueues a job of kind &#x60;dict-refresh&#x60; (payload &#x60;JobPayloadDictRefresh&#x60;, dedupe key &#x60;dict-refresh&#x60;). An already queued or running refresh is returned with &#x60;200&#x60;. An absent body equals &#x60;{}&#x60;. 

### Example

```ts
import {
  Configuration,
  DictionaryApi,
} from '@kotobaya/api-client';
import type { RefreshDictionaryRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DictionaryApi(config);

  const body = {
    // JobPayloadDictRefresh (optional)
    jobPayloadDictRefresh: {"freq_lists":["jpdb","bccwj"]},
  } satisfies RefreshDictionaryRequest;

  try {
    const data = await api.refreshDictionary(body);
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
| **jobPayloadDictRefresh** | [JobPayloadDictRefresh](JobPayloadDictRefresh.md) |  | [Optional] |

### Return type

[**Job**](Job.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | A refresh is already queued or running. |  -  |
| **202** | Job queued. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## searchDictionary

> DictEntrySummaryPage searchDictionary(q, commonOnly, limit, cursor)

Search JMdict entries by kanji, kana, romaji or English.

&#x60;q&#x60; is trimmed and NFKC-normalised. Interpretation, in order: 1. Contains kanji or kana: katakana is folded to hiragana and matched against    &#x60;dict_entry_form.norm&#x60; - 1 or 2 characters: exact or prefix; 3 or more: substring. 2. Otherwise (Latin letters): the server\&#39;s romaji-to-kana conversion (wanakana-equivalent:    Hepburn and common IME spellings, &#x60;nn&#x60;/&#x60;n\&#39;&#x60; for ん, doubled consonant for っ) is tried    and, if the whole string converts, matched as in 1; the string is also matched    case-insensitively as a substring of English glosses. Both result sets are merged. No deinflection: the input is matched as given (&#x60;たべた&#x60; does not find &#x60;食べる&#x60; unless a form of that entry contains &#x60;たべた&#x60;). Deinflection is the parser\&#39;s job (&#x60;POST /parse&#x60;), not this search\&#39;s (C-2). Retired entries are never returned. Order: exact form match first, then prefix, then the rest; inside each group &#x60;freq_rank&#x60; ascending with NULLs last, then &#x60;seq&#x60;. An empty or whitespace-only &#x60;q&#x60; is &#x60;422&#x60;. No match is an empty page. Each item carries the caller\&#39;s &#x60;status&#x60; and &#x60;note_id&#x60; overlay. 

### Example

```ts
import {
  Configuration,
  DictionaryApi,
} from '@kotobaya/api-client';
import type { SearchDictionaryRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DictionaryApi(config);

  const body = {
    // string | Search text, 1 to 100 characters after trimming.
    q: たべる,
    // boolean | Only entries with `is_common = true`. (optional)
    commonOnly: false,
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies SearchDictionaryRequest;

  try {
    const data = await api.searchDictionary(body);
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
| **q** | `string` | Search text, 1 to 100 characters after trimming. | [Defaults to `undefined`] |
| **commonOnly** | `boolean` | Only entries with &#x60;is_common &#x3D; true&#x60;. | [Optional] [Defaults to `false`] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**DictEntrySummaryPage**](DictEntrySummaryPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of matching entries. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

