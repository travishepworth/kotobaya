# DictionaryApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**getDictEntry**](DictionaryApi.md#getDictEntry) | **GET** /dict/entries/{seq} | One JMdict entry with frequency, pitch, kanji and your status. |
| [**getDictRelease**](DictionaryApi.md#getDictRelease) | **GET** /dict/release | The current dictionary release and its downloadable device builds. |
| [**getKanji**](DictionaryApi.md#getKanji) | **GET** /kanji/{literal} | One kanji from KANJIDIC2 with components and your kanji note, if any. |
| [**listEntryExamples**](DictionaryApi.md#listEntryExamples) | **GET** /dict/entries/{seq}/examples | Example sentences for an entry, from your media and from Tatoeba. |
| [**listKanjiWords**](DictionaryApi.md#listKanjiWords) | **GET** /kanji/{literal}/words | Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17). |
| [**refreshDictionary**](DictionaryApi.md#refreshDictionary) | **POST** /dict/refresh | Queue a dictionary refresh (download sources, build and load a new release). |
| [**searchDictionary**](DictionaryApi.md#searchDictionary) | **GET** /dict/search | Search JMdict entries by kanji, kana, romaji or English. |


<a id="getDictEntry"></a>
# **getDictEntry**
> DictEntry getDictEntry(seq)

One JMdict entry with frequency, pitch, kanji and your status.

Retired entries are still returned (&#x60;retired&#x60; is true). Unknown &#x60;seq&#x60; is &#x60;404&#x60;.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DictionaryApi()
val seq : kotlin.Long = 1358280 // kotlin.Long | JMdict sequence number of the entry.
try {
    val result : DictEntry = apiInstance.getDictEntry(seq)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DictionaryApi#getDictEntry")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DictionaryApi#getDictEntry")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **seq** | **kotlin.Long**| JMdict sequence number of the entry. | |

### Return type

[**DictEntry**](DictEntry.md)

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
 - **Accept**: application/json, application/problem+json

<a id="getDictRelease"></a>
# **getDictRelease**
> DictRelease getDictRelease()

The current dictionary release and its downloadable device builds.

The phone compares &#x60;id&#x60; with the release of its dictionary file and, when behind, downloads an artifact through &#x60;GET /blobs/{hash}&#x60;: a &#x60;diff&#x60; whose &#x60;from_release&#x60; equals its release and whose &#x60;scope&#x60; matches, else the &#x60;full&#x60; build. &#x60;404&#x60; when no release has been loaded yet. Package and diff formats, hashes and the full install/update algorithm: &#x60;contracts/db/DICT_PACKAGE.md&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DictionaryApi()
try {
    val result : DictRelease = apiInstance.getDictRelease()
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DictionaryApi#getDictRelease")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DictionaryApi#getDictRelease")
    e.printStackTrace()
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

[**DictRelease**](DictRelease.md)

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
 - **Accept**: application/json, application/problem+json

<a id="getKanji"></a>
# **getKanji**
> Kanji getKanji(literal)

One kanji from KANJIDIC2 with components and your kanji note, if any.

More than one code point is &#x60;422&#x60;; a character not in &#x60;ref.kanji&#x60; is &#x60;404&#x60;. (&#x60;maxLength: 2&#x60; because a code point outside the BMP is two UTF-16 units.) 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DictionaryApi()
val literal : kotlin.String = 績 // kotlin.String | Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment `hot` is the separate operation `listHotKanji`.
try {
    val result : Kanji = apiInstance.getKanji(literal)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DictionaryApi#getKanji")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DictionaryApi#getKanji")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **literal** | **kotlin.String**| Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment &#x60;hot&#x60; is the separate operation &#x60;listHotKanji&#x60;. | |

### Return type

[**Kanji**](Kanji.md)

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
 - **Accept**: application/json, application/problem+json

<a id="listEntryExamples"></a>
# **listEntryExamples**
> EntryExamples listEntryExamples(seq, limit)

Example sentences for an entry, from your media and from Tatoeba.

&#x60;media&#x60;: up to &#x60;limit&#x60; sentences from imported media that contain the entry (resolved through &#x60;occurrence.sentence_ids&#x60;), sentences with the fewest unknown words first, then by media &#x60;created_at&#x60; descending, then sentence id. &#x60;tatoeba&#x60;: up to &#x60;limit&#x60; rows of &#x60;ref.example_sentence&#x60; linked to the entry, shortest text first, then id. Either list may be empty. Not paginated. Unknown &#x60;seq&#x60; is &#x60;404&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DictionaryApi()
val seq : kotlin.Long = 1358280 // kotlin.Long | JMdict sequence number of the entry.
val limit : kotlin.Int = 10 // kotlin.Int | Maximum number of sentences per list.
try {
    val result : EntryExamples = apiInstance.listEntryExamples(seq, limit)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DictionaryApi#listEntryExamples")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DictionaryApi#listEntryExamples")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **seq** | **kotlin.Long**| JMdict sequence number of the entry. | |
| **limit** | **kotlin.Int**| Maximum number of sentences per list. | [optional] [default to 10] |

### Return type

[**EntryExamples**](EntryExamples.md)

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
 - **Accept**: application/json, application/problem+json

<a id="listKanjiWords"></a>
# **listKanjiWords**
> KanjiWordPage listKanjiWords(literal, deckId, mine, limit, cursor)

Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17).

Rows: the live (&#x60;retired_release_id IS NULL&#x60;) &#x60;ref.dict_entry&#x60; rows &#x60;e&#x60; with a &#x60;ref.entry_kanji&#x60; row &#x60;(e.seq, literal)&#x60;. Retired entries are never returned.  **Literal.** More than one code point (or an empty string) is &#x60;422&#x60;. Unlike &#x60;getKanji&#x60;, a character absent from &#x60;ref.kanji&#x60; is **not** &#x60;404&#x60;: &#x60;ref.entry_kanji.literal&#x60; need not exist in &#x60;ref.kanji&#x60;, so such a character, or any code point no entry contains (kana, Latin...), is simply an empty page (&#x60;items: []&#x60;, &#x60;next_cursor: null&#x60;).  **Filters** (combine with AND): - &#x60;deck_id&#x60;: only entries &#x60;e&#x60; with a live item of that deck: an &#x60;app.deck_item&#x60; row with   &#x60;deck_id&#x60; &#x3D; the parameter, &#x60;entry_seq &#x3D; e.seq&#x60; and &#x60;deleted_at IS NULL&#x60;. The deck must   be a live deck of the caller (&#x60;app.deck.deleted_at IS NULL&#x60;), else &#x60;404&#x60; - a deleted   deck is &#x60;404&#x60; even though its rows remain. A live deck with no matching item (including   every &#x60;smart&#x60; deck, which has no items) gives an empty page. - &#x60;mine&#x3D;true&#x60;: only entries that are a live item (as above) of at least one live deck of   the caller, **or** that have a live vocab note (&#x60;app.note&#x60; with &#x60;entry_seq &#x3D; e.seq&#x60;,   &#x60;deleted_at IS NULL&#x60;, base type &#x60;vocab&#x60; - the same note the &#x60;note_id&#x60; overlay reports).   Word status alone (e.g. &#x60;known&#x60; set by hand) does not count. With &#x60;deck_id&#x60; also given,   &#x60;mine&#x3D;true&#x60; adds nothing (every result is already in one of your decks). - Note-keyed deck items (&#x60;deck_item.note_id&#x60;, &#x60;entry_seq IS NULL&#x60;) never match: only   &#x60;entry_seq&#x60; items count, here and in &#x60;decks&#x60;.  **Order** (total; &#x60;seq&#x60; is unique): - &#x60;deck_id&#x60; given: that deck&#39;s &#x60;deck_item.occurrence_count&#x60; descending (in-deck frequency;   all &#x60;0&#x60; for non-media decks, so this degrades to the global order), then   &#x60;freq_rank&#x60; ascending with NULLs last, then &#x60;seq&#x60; ascending. - Otherwise: &#x60;freq_rank&#x60; ascending with NULLs last, then &#x60;seq&#x60; ascending.  **Paging.** Keyset pagination: the cursor encodes the sort key of the last returned row and the &#x60;literal&#x60;, &#x60;deck_id&#x60; and &#x60;mine&#x60; it was issued for; reusing it with a different &#x60;literal&#x60;, &#x60;deck_id&#x60; or &#x60;mine&#x60; is &#x60;422&#x60;. Pages are not a snapshot: if &#x60;occurrence_count&#x60; or &#x60;freq_rank&#x60; changes between pages (media reparse, dictionary refresh), a row may be skipped or repeated; clients that page through MUST de-duplicate by &#x60;seq&#x60;. &#x60;next_cursor&#x60; is null on the last page (it MAY be non-null on a page that happens to end exactly at the last row; the following page is then empty).  **Item.** Every &#x60;DictEntrySummary&#x60; field with the same meaning as in &#x60;searchDictionary&#x60; (caller&#39;s &#x60;status&#x60; and &#x60;note_id&#x60; overlay), plus &#x60;decks&#x60;: the caller&#39;s live decks (&#x60;app.deck.deleted_at IS NULL&#x60;) that hold the entry as a live item (&#x60;deck_item.deleted_at IS NULL&#x60;), ordered by &#x60;name&#x60; in code-point order (&#x60;COLLATE \&quot;C\&quot;&#x60;), then &#x60;id&#x60;. Empty array when none. Independent of &#x60;deck_id&#x60;/&#x60;mine&#x60;: with &#x60;deck_id&#x60;, that deck is always among them. &#x60;decks[].occurrence_count&#x60; is that deck&#39;s &#x60;deck_item.occurrence_count&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DictionaryApi()
val literal : kotlin.String = 食 // kotlin.String | Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against `ref.entry_kanji.literal` (no normalisation, same rules as `getKanji`).
val deckId : kotlin.String = 01931f40-2000-7000-8000-000000000d01 // kotlin.String | Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (`404` otherwise).
val mine : kotlin.Boolean = false // kotlin.Boolean | Only entries that are a live item of at least one of your live decks or have a live vocab note.
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : KanjiWordPage = apiInstance.listKanjiWords(literal, deckId, mine, limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DictionaryApi#listKanjiWords")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DictionaryApi#listKanjiWords")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **literal** | **kotlin.String**| Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against &#x60;ref.entry_kanji.literal&#x60; (no normalisation, same rules as &#x60;getKanji&#x60;). | |
| **deckId** | **kotlin.String**| Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (&#x60;404&#x60; otherwise). | [optional] |
| **mine** | **kotlin.Boolean**| Only entries that are a live item of at least one of your live decks or have a live vocab note. | [optional] [default to false] |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**KanjiWordPage**](KanjiWordPage.md)

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
 - **Accept**: application/json, application/problem+json

<a id="refreshDictionary"></a>
# **refreshDictionary**
> Job refreshDictionary(jobPayloadDictRefresh)

Queue a dictionary refresh (download sources, build and load a new release).

Enqueues a job of kind &#x60;dict-refresh&#x60; (payload &#x60;JobPayloadDictRefresh&#x60;, dedupe key &#x60;dict-refresh&#x60;). An already queued or running refresh is returned with &#x60;200&#x60;. An absent body equals &#x60;{}&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DictionaryApi()
val jobPayloadDictRefresh : JobPayloadDictRefresh = {"freq_lists":["jpdb","bccwj"]} // JobPayloadDictRefresh | 
try {
    val result : Job = apiInstance.refreshDictionary(jobPayloadDictRefresh)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DictionaryApi#refreshDictionary")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DictionaryApi#refreshDictionary")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **jobPayloadDictRefresh** | [**JobPayloadDictRefresh**](JobPayloadDictRefresh.md)|  | [optional] |

### Return type

[**Job**](Job.md)

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

<a id="searchDictionary"></a>
# **searchDictionary**
> DictEntrySummaryPage searchDictionary(q, commonOnly, limit, cursor)

Search JMdict entries by kanji, kana, romaji or English.

&#x60;q&#x60; is trimmed and NFKC-normalised. Interpretation, in order: 1. Contains kanji or kana: katakana is folded to hiragana and matched against    &#x60;dict_entry_form.norm&#x60; - 1 or 2 characters: exact or prefix; 3 or more: substring. 2. Otherwise (Latin letters): the server&#39;s romaji-to-kana conversion (wanakana-equivalent:    Hepburn and common IME spellings, &#x60;nn&#x60;/&#x60;n&#39;&#x60; for ん, doubled consonant for っ) is tried    and, if the whole string converts, matched as in 1; the string is also matched    case-insensitively as a substring of English glosses. Both result sets are merged. No deinflection: the input is matched as given (&#x60;たべた&#x60; does not find &#x60;食べる&#x60; unless a form of that entry contains &#x60;たべた&#x60;). Deinflection is the parser&#39;s job (&#x60;POST /parse&#x60;), not this search&#39;s (C-2). Retired entries are never returned. Order: exact form match first, then prefix, then the rest; inside each group &#x60;freq_rank&#x60; ascending with NULLs last, then &#x60;seq&#x60;. An empty or whitespace-only &#x60;q&#x60; is &#x60;422&#x60;. No match is an empty page. Each item carries the caller&#39;s &#x60;status&#x60; and &#x60;note_id&#x60; overlay. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = DictionaryApi()
val q : kotlin.String = たべる // kotlin.String | Search text, 1 to 100 characters after trimming.
val commonOnly : kotlin.Boolean = false // kotlin.Boolean | Only entries with `is_common = true`.
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : DictEntrySummaryPage = apiInstance.searchDictionary(q, commonOnly, limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DictionaryApi#searchDictionary")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DictionaryApi#searchDictionary")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **q** | **kotlin.String**| Search text, 1 to 100 characters after trimming. | |
| **commonOnly** | **kotlin.Boolean**| Only entries with &#x60;is_common &#x3D; true&#x60;. | [optional] [default to false] |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**DictEntrySummaryPage**](DictEntrySummaryPage.md)

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
 - **Accept**: application/json, application/problem+json

