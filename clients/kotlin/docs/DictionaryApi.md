# DictionaryApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**getDictEntry**](DictionaryApi.md#getDictEntry) | **GET** /dict/entries/{seq} | One JMdict entry with frequency, pitch, kanji and your status. |
| [**getDictRelease**](DictionaryApi.md#getDictRelease) | **GET** /dict/release | The current dictionary release and its downloadable device builds. |
| [**getKanji**](DictionaryApi.md#getKanji) | **GET** /kanji/{literal} | One kanji from KANJIDIC2 with components and your kanji note, if any. |
| [**listEntryExamples**](DictionaryApi.md#listEntryExamples) | **GET** /dict/entries/{seq}/examples | Example sentences for an entry, from your media and from Tatoeba. |
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

