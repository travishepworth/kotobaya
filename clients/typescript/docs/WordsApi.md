# WordsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**clearWordStatus**](WordsApi.md#clearwordstatus) | **DELETE** /words/{seq}/status | Clear the override; the status falls back to the derived one. |
| [**getWordStatus**](WordsApi.md#getwordstatus) | **GET** /words/{seq}/status | Word status of one entry. |
| [**listConfusables**](WordsApi.md#listconfusables) | **GET** /confusables | All confusable pairs (any note type). |
| [**listHotKanji**](WordsApi.md#listhotkanji) | **GET** /kanji/hot | Hot kanji - recent failures rolled up by kanji. |
| [**listHotWords**](WordsApi.md#listhotwords) | **GET** /words/hot | Hot words - recent struggle, not lifetime. |
| [**listLeeches**](WordsApi.md#listleeches) | **GET** /words/leeches | Leech cards (lapses at or above &#x60;scheduler.leech_threshold&#x60;). |
| [**listWordConfusables**](WordsApi.md#listwordconfusables) | **GET** /words/{seq}/confusables | Confusable partners of the word\&#39;s note. |
| [**setConfusable**](WordsApi.md#setconfusable) | **PUT** /confusables/{note_a_id}/{note_b_id}/{reason} | Confirm or reject a suggested pair, or record a pair yourself. |
| [**setWordStatus**](WordsApi.md#setwordstatus) | **PUT** /words/{seq}/status | Set an explicit status override (known, never-forget, blacklisted, ignored). |



## clearWordStatus

> WordStatus clearWordStatus(seq)

Clear the override; the status falls back to the derived one.

Server emits &#x60;word.status_cleared&#x60;. No override present (or no row): &#x60;200&#x60; with the current status, no op. 

### Example

```ts
import {
  Configuration,
  WordsApi,
} from '@kotobaya/api-client';
import type { ClearWordStatusRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new WordsApi(config);

  const body = {
    // number | JMdict sequence number of the entry.
    seq: 1358280,
  } satisfies ClearWordStatusRequest;

  try {
    const data = await api.clearWordStatus(body);
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

[**WordStatus**](WordStatus.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The word status after the change. |  * Kotobaya-Seq -  <br>  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getWordStatus

> WordStatus getWordStatus(seq)

Word status of one entry.

An entry without a &#x60;word_status&#x60; row is reported as &#x60;status &#x3D; new&#x60; with every other property null. The entry need not exist in the dictionary.

### Example

```ts
import {
  Configuration,
  WordsApi,
} from '@kotobaya/api-client';
import type { GetWordStatusRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new WordsApi(config);

  const body = {
    // number | JMdict sequence number of the entry.
    seq: 1358280,
  } satisfies GetWordStatusRequest;

  try {
    const data = await api.getWordStatus(body);
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

[**WordStatus**](WordStatus.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The word status. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listConfusables

> ConfusablePage listConfusables(status, noteId, limit, cursor)

All confusable pairs (any note type).

Ordered by &#x60;updated_at&#x60; descending, then &#x60;(note_a_id, note_b_id, reason)&#x60;. Pairs with a deleted note are hidden; merged notes are resolved to survivors.

### Example

```ts
import {
  Configuration,
  WordsApi,
} from '@kotobaya/api-client';
import type { ListConfusablesRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new WordsApi(config);

  const body = {
    // ConfusableStatus | Only pairs with this status. Default - all three. (optional)
    status: suggested,
    // string | Only pairs involving this note. (optional)
    noteId: 01931f40-1a2b-7c3d-8e4f-000000000a01,
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListConfusablesRequest;

  try {
    const data = await api.listConfusables(body);
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
| **status** | `ConfusableStatus` | Only pairs with this status. Default - all three. | [Optional] [Defaults to `undefined`] [Enum: suggested, confirmed, rejected] |
| **noteId** | `string` | Only pairs involving this note. | [Optional] [Defaults to `undefined`] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**ConfusablePage**](ConfusablePage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of pairs. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listHotKanji

> HotKanjiList listHotKanji(limit)

Hot kanji - recent failures rolled up by kanji.

Scores come from the shared core (hot-word score summed over every live vocab note whose entry contains the kanji, via &#x60;ref.entry_kanji&#x60;). Highest score first, then &#x60;literal&#x60;. Only kanji with a score above zero. Not paginated. 

### Example

```ts
import {
  Configuration,
  WordsApi,
} from '@kotobaya/api-client';
import type { ListHotKanjiRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new WordsApi(config);

  const body = {
    // number | Maximum number of kanji. (optional)
    limit: 20,
  } satisfies ListHotKanjiRequest;

  try {
    const data = await api.listHotKanji(body);
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
| **limit** | `number` | Maximum number of kanji. | [Optional] [Defaults to `20`] |

### Return type

[**HotKanjiList**](HotKanjiList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Hot kanji. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listHotWords

> HotWordList listHotWords(limit)

Hot words - recent struggle, not lifetime.

Score from the shared core (failures over the last 14 days with decay, FSRS difficulty, lapse rate, relearning failures). Only live vocab notes with a score above zero; highest score first, then note id. Not paginated. 

### Example

```ts
import {
  Configuration,
  WordsApi,
} from '@kotobaya/api-client';
import type { ListHotWordsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new WordsApi(config);

  const body = {
    // number | Maximum number of words. (optional)
    limit: 20,
  } satisfies ListHotWordsRequest;

  try {
    const data = await api.listHotWords(body);
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
| **limit** | `number` | Maximum number of words. | [Optional] [Defaults to `20`] |

### Return type

[**HotWordList**](HotWordList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Hot words. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listLeeches

> CardPage listLeeches(limit, cursor)

Leech cards (lapses at or above &#x60;scheduler.leech_threshold&#x60;).

Live cards of live notes with &#x60;lapses &gt;&#x3D; scheduler.leech_threshold&#x60;, suspended ones included. Ordered by &#x60;lapses&#x60; descending, then card id. Covers every note type, not only vocab. Equivalent to &#x60;GET /cards?query&#x3D;tag:leech&amp;sort&#x3D;-lapses&#x60;. 

### Example

```ts
import {
  Configuration,
  WordsApi,
} from '@kotobaya/api-client';
import type { ListLeechesRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new WordsApi(config);

  const body = {
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListLeechesRequest;

  try {
    const data = await api.listLeeches(body);
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
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**CardPage**](CardPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of leech cards. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listWordConfusables

> ConfusableList listWordConfusables(seq, status)

Confusable partners of the word\&#39;s note.

Pairs involving the live vocab note of this entry, resolved through &#x60;merged_into_id&#x60;, pairs with a deleted note hidden. Ordered: &#x60;confirmed&#x60; first, then &#x60;suggested&#x60;; inside by &#x60;updated_at&#x60; descending. &#x60;rejected&#x60; pairs only with &#x60;status&#x3D;rejected&#x60;. No note for the entry: empty list. Not paginated. 

### Example

```ts
import {
  Configuration,
  WordsApi,
} from '@kotobaya/api-client';
import type { ListWordConfusablesRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new WordsApi(config);

  const body = {
    // number | JMdict sequence number of the entry.
    seq: 1358280,
    // ConfusableStatus | Only pairs with this status. Default - `suggested` and `confirmed`. (optional)
    status: confirmed,
  } satisfies ListWordConfusablesRequest;

  try {
    const data = await api.listWordConfusables(body);
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
| **status** | `ConfusableStatus` | Only pairs with this status. Default - &#x60;suggested&#x60; and &#x60;confirmed&#x60;. | [Optional] [Defaults to `undefined`] [Enum: suggested, confirmed, rejected] |

### Return type

[**ConfusableList**](ConfusableList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Confusable pairs. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## setConfusable

> Confusable setConfusable(noteAId, noteBId, reason, confusableSet)

Confirm or reject a suggested pair, or record a pair yourself.

Server emits &#x60;confusable.confirmed&#x60; or &#x60;confusable.rejected&#x60;. If the pair does not exist yet the op creates it (a hand-made pair is simply a confirmed one). Both notes must exist; merge losers are resolved to survivors first, and if both resolve to the same note the call is &#x60;422&#x60;. A user decision always beats a suggestion; between decisions the later clock wins. Rejected pairs are kept so they are not suggested again. 

### Example

```ts
import {
  Configuration,
  WordsApi,
} from '@kotobaya/api-client';
import type { SetConfusableRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new WordsApi(config);

  const body = {
    // string | One note of the pair. The two ids may be given in either order; the server stores the smaller first.
    noteAId: 01931f40-1a2b-7c3d-8e4f-000000000a01,
    // string | The other note. Must differ from `note_a_id` (`422`).
    noteBId: 01931f40-1a2b-7c3d-8e4f-000000000a02,
    // ConfusableReason | Why the two are confusable.
    reason: look_alike,
    // ConfusableSet
    confusableSet: {"status":"confirmed"},
  } satisfies SetConfusableRequest;

  try {
    const data = await api.setConfusable(body);
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
| **noteAId** | `string` | One note of the pair. The two ids may be given in either order; the server stores the smaller first. | [Defaults to `undefined`] |
| **noteBId** | `string` | The other note. Must differ from &#x60;note_a_id&#x60; (&#x60;422&#x60;). | [Defaults to `undefined`] |
| **reason** | `ConfusableReason` | Why the two are confusable. | [Defaults to `undefined`] [Enum: homophone, look_alike, meaning] |
| **confusableSet** | [ConfusableSet](ConfusableSet.md) |  | |

### Return type

[**Confusable**](Confusable.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The pair as stored. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## setWordStatus

> WordStatus setWordStatus(seq, wordStatusSet)

Set an explicit status override (known, never-forget, blacklisted, ignored).

Server emits &#x60;word.status_set&#x60; with payload &#x60;origin &#x3D; manual&#x60; (stored as &#x60;override_origin&#x60;). &#x60;new&#x60; and &#x60;learning&#x60; cannot be set (they are derived): to return to the derived status use &#x60;DELETE&#x60;. The entry need not exist in the dictionary and need not have a note. Setting the status it already has still emits (bumps the clock). 

### Example

```ts
import {
  Configuration,
  WordsApi,
} from '@kotobaya/api-client';
import type { SetWordStatusRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new WordsApi(config);

  const body = {
    // number | JMdict sequence number of the entry.
    seq: 1358280,
    // WordStatusSet
    wordStatusSet: {"status":"blacklisted"},
  } satisfies SetWordStatusRequest;

  try {
    const data = await api.setWordStatus(body);
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
| **wordStatusSet** | [WordStatusSet](WordStatusSet.md) |  | |

### Return type

[**WordStatus**](WordStatus.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The word status after the change. |  * Kotobaya-Seq -  <br>  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

