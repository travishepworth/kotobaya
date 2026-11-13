# DictionaryApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getDictEntry**](DictionaryApi.md#getdictentry) | **GET** /dict/entries/{seq} | One JMdict entry with frequency, pitch, kanji and your status. |
| [**getDictRelease**](DictionaryApi.md#getdictrelease) | **GET** /dict/release | The current dictionary release and its downloadable device builds. |
| [**getKanji**](DictionaryApi.md#getkanji) | **GET** /kanji/{literal} | One kanji from KANJIDIC2 with components and your kanji note, if any. |
| [**listDictHeadwords**](DictionaryApi.md#listdictheadwords) | **GET** /dict/headwords | Headword, reading and first-sense glosses for up to 500 entries in one call (C-18). |
| [**listEntryExamples**](DictionaryApi.md#listentryexamples) | **GET** /dict/entries/{seq}/examples | Example sentences for an entry, from your media and from Tatoeba. |
| [**listKanjiWords**](DictionaryApi.md#listkanjiwords) | **GET** /kanji/{literal}/words | Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17). |
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


## listDictHeadwords

> DictHeadwordList listDictHeadwords(seq)

Headword, reading and first-sense glosses for up to 500 entries in one call (C-18).

Batch label lookup for clients that hold only &#x60;entry_seq&#x60; values (e.g. a page of referenced notes whose &#x60;fields&#x60; are &#x60;{}&#x60;). Read-only; no overlay (&#x60;status&#x60;, &#x60;note_id&#x60;), no paging. Use &#x60;getDictEntry&#x60; for the full entry.  **&#x60;seq&#x60;.** One query parameter holding 1 to 500 JMdict sequence numbers separated by commas (&#x60;seq&#x3D;1358280,1358300&#x60;; a percent-encoded comma &#x60;%2C&#x60; is the same thing). Each value is a base-10 integer &gt;&#x3D; 1 with no sign, spaces or decimals. Any of these is &#x60;422&#x60; (&#x60;validation_failed&#x60;, pointer &#x60;/seq&#x60;) and nothing is looked up: the parameter is missing, empty (&#x60;seq&#x3D;&#x60;) or given more than once (&#x60;seq&#x3D;1&amp;seq&#x3D;2&#x60;); an element is empty (&#x60;1,,2&#x60;, trailing comma), not an integer, below 1 or above the int64 range; more than 500 elements. The 500 limit counts elements as sent, before de-duplication. Duplicates are allowed and collapse: &#x60;seq&#x3D;5,5&#x60; behaves as &#x60;seq&#x3D;5&#x60;.  **Result.** One item per distinct requested &#x60;seq&#x60; that exists in &#x60;ref.dict_entry&#x60;, ordered by &#x60;seq&#x60; ascending (not in request order). A &#x60;seq&#x60; with no row is omitted - not an error, and not &#x60;404&#x60; even when no requested &#x60;seq&#x60; exists (&#x60;items: []&#x60;). Retired entries are returned, as in &#x60;getDictEntry&#x60; (a note may still reference one). Clients match items to their rows by &#x60;seq&#x60; and keep their fallback label for omitted ones.  **Item fields** have exactly the values &#x60;getDictEntry&#x60; and &#x60;searchDictionary&#x60; report for the same entry: &#x60;headword&#x60; &#x3D; first kanji form in JMdict order, or the first reading when the entry has no kanji form; &#x60;headword_reading&#x60; &#x3D; first reading (equal to &#x60;headword&#x60; for kana-only entries); &#x60;glosses&#x60; &#x3D; English glosses of the first sense, in order (the first element is the short meaning; empty array if the first sense has no English gloss).  **Request size.** 500 seven-digit values with percent-encoded commas make a request line of about 5 kB. The server and every proxy in front of it MUST accept request lines of at least 8192 bytes for this operation (Netty\&#39;s default of 4096 is too small).  The response depends only on the dictionary release, not on the caller; it carries no cache headers in v1. 

### Example

```ts
import {
  Configuration,
  DictionaryApi,
} from '@kotobaya/api-client';
import type { ListDictHeadwordsRequest } from '@kotobaya/api-client';

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
    // Array<number> | 1 to 500 JMdict sequence numbers, comma-separated. Duplicates collapse; unknown values are omitted from the result.
    seq: [1358280,1358300,9999999],
  } satisfies ListDictHeadwordsRequest;

  try {
    const data = await api.listDictHeadwords(body);
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
| **seq** | `Array<number>` | 1 to 500 JMdict sequence numbers, comma-separated. Duplicates collapse; unknown values are omitted from the result. | |

### Return type

[**DictHeadwordList**](DictHeadwordList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Headwords of the requested entries that exist, by &#x60;seq&#x60; ascending. Empty list when none exist. |  -  |
| **401** | Missing, unknown, expired or revoked credential; or wrong login or pairing data. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
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


## listKanjiWords

> KanjiWordPage listKanjiWords(literal, deckId, mine, limit, cursor)

Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17).

Rows: the live (&#x60;retired_release_id IS NULL&#x60;) &#x60;ref.dict_entry&#x60; rows &#x60;e&#x60; with a &#x60;ref.entry_kanji&#x60; row &#x60;(e.seq, literal)&#x60;. Retired entries are never returned.  **Literal.** More than one code point (or an empty string) is &#x60;422&#x60;. Unlike &#x60;getKanji&#x60;, a character absent from &#x60;ref.kanji&#x60; is **not** &#x60;404&#x60;: &#x60;ref.entry_kanji.literal&#x60; need not exist in &#x60;ref.kanji&#x60;, so such a character, or any code point no entry contains (kana, Latin...), is simply an empty page (&#x60;items: []&#x60;, &#x60;next_cursor: null&#x60;).  **Filters** (combine with AND): - &#x60;deck_id&#x60;: only entries &#x60;e&#x60; with a live item of that deck: an &#x60;app.deck_item&#x60; row with   &#x60;deck_id&#x60; &#x3D; the parameter, &#x60;entry_seq &#x3D; e.seq&#x60; and &#x60;deleted_at IS NULL&#x60;. The deck must   be a live deck of the caller (&#x60;app.deck.deleted_at IS NULL&#x60;), else &#x60;404&#x60; - a deleted   deck is &#x60;404&#x60; even though its rows remain. A live deck with no matching item (including   every &#x60;smart&#x60; deck, which has no items) gives an empty page. - &#x60;mine&#x3D;true&#x60;: only entries that are a live item (as above) of at least one live deck of   the caller, **or** that have a live vocab note (&#x60;app.note&#x60; with &#x60;entry_seq &#x3D; e.seq&#x60;,   &#x60;deleted_at IS NULL&#x60;, base type &#x60;vocab&#x60; - the same note the &#x60;note_id&#x60; overlay reports).   Word status alone (e.g. &#x60;known&#x60; set by hand) does not count. With &#x60;deck_id&#x60; also given,   &#x60;mine&#x3D;true&#x60; adds nothing (every result is already in one of your decks). - Note-keyed deck items (&#x60;deck_item.note_id&#x60;, &#x60;entry_seq IS NULL&#x60;) never match: only   &#x60;entry_seq&#x60; items count, here and in &#x60;decks&#x60;.  **Order** (total; &#x60;seq&#x60; is unique): - &#x60;deck_id&#x60; given: that deck\&#39;s &#x60;deck_item.occurrence_count&#x60; descending (in-deck frequency;   all &#x60;0&#x60; for non-media decks, so this degrades to the global order), then   &#x60;freq_rank&#x60; ascending with NULLs last, then &#x60;seq&#x60; ascending. - Otherwise: &#x60;freq_rank&#x60; ascending with NULLs last, then &#x60;seq&#x60; ascending.  **Paging.** Keyset pagination: the cursor encodes the sort key of the last returned row and the &#x60;literal&#x60;, &#x60;deck_id&#x60; and &#x60;mine&#x60; it was issued for; reusing it with a different &#x60;literal&#x60;, &#x60;deck_id&#x60; or &#x60;mine&#x60; is &#x60;422&#x60;. Pages are not a snapshot: if &#x60;occurrence_count&#x60; or &#x60;freq_rank&#x60; changes between pages (media reparse, dictionary refresh), a row may be skipped or repeated; clients that page through MUST de-duplicate by &#x60;seq&#x60;. &#x60;next_cursor&#x60; is null on the last page (it MAY be non-null on a page that happens to end exactly at the last row; the following page is then empty).  **Item.** Every &#x60;DictEntrySummary&#x60; field with the same meaning as in &#x60;searchDictionary&#x60; (caller\&#39;s &#x60;status&#x60; and &#x60;note_id&#x60; overlay), plus &#x60;decks&#x60;: the caller\&#39;s live decks (&#x60;app.deck.deleted_at IS NULL&#x60;) that hold the entry as a live item (&#x60;deck_item.deleted_at IS NULL&#x60;), ordered by &#x60;name&#x60; in code-point order (&#x60;COLLATE \&quot;C\&quot;&#x60;), then &#x60;id&#x60;. Empty array when none. Independent of &#x60;deck_id&#x60;/&#x60;mine&#x60;: with &#x60;deck_id&#x60;, that deck is always among them. &#x60;decks[].occurrence_count&#x60; is that deck\&#39;s &#x60;deck_item.occurrence_count&#x60;. 

### Example

```ts
import {
  Configuration,
  DictionaryApi,
} from '@kotobaya/api-client';
import type { ListKanjiWordsRequest } from '@kotobaya/api-client';

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
    // string | Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against `ref.entry_kanji.literal` (no normalisation, same rules as `getKanji`).
    literal: 食,
    // string | Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (`404` otherwise). (optional)
    deckId: 01931f40-2000-7000-8000-000000000d01,
    // boolean | Only entries that are a live item of at least one of your live decks or have a live vocab note. (optional)
    mine: false,
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListKanjiWordsRequest;

  try {
    const data = await api.listKanjiWords(body);
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
| **literal** | `string` | Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against &#x60;ref.entry_kanji.literal&#x60; (no normalisation, same rules as &#x60;getKanji&#x60;). | [Defaults to `undefined`] |
| **deckId** | `string` | Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (&#x60;404&#x60; otherwise). | [Optional] [Defaults to `undefined`] |
| **mine** | `boolean` | Only entries that are a live item of at least one of your live decks or have a live vocab note. | [Optional] [Defaults to `false`] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**KanjiWordPage**](KanjiWordPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of entries containing the kanji. Empty page when none do. |  -  |
| **404** | No such resource. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
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

