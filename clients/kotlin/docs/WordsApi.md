# WordsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**clearWordStatus**](WordsApi.md#clearWordStatus) | **DELETE** /words/{seq}/status | Clear the override; the status falls back to the derived one. |
| [**getWordStatus**](WordsApi.md#getWordStatus) | **GET** /words/{seq}/status | Word status of one entry. |
| [**listConfusables**](WordsApi.md#listConfusables) | **GET** /confusables | All confusable pairs (any note type). |
| [**listHotKanji**](WordsApi.md#listHotKanji) | **GET** /kanji/hot | Hot kanji - recent failures rolled up by kanji. |
| [**listHotWords**](WordsApi.md#listHotWords) | **GET** /words/hot | Hot words - recent struggle, not lifetime. |
| [**listLeeches**](WordsApi.md#listLeeches) | **GET** /words/leeches | Leech cards (lapses at or above &#x60;scheduler.leech_threshold&#x60;). |
| [**listWordConfusables**](WordsApi.md#listWordConfusables) | **GET** /words/{seq}/confusables | Confusable partners of the word&#39;s note. |
| [**setConfusable**](WordsApi.md#setConfusable) | **PUT** /confusables/{note_a_id}/{note_b_id}/{reason} | Confirm or reject a suggested pair, or record a pair yourself. |
| [**setWordStatus**](WordsApi.md#setWordStatus) | **PUT** /words/{seq}/status | Set an explicit status override (known, never-forget, blacklisted, ignored). |


<a id="clearWordStatus"></a>
# **clearWordStatus**
> WordStatus clearWordStatus(seq)

Clear the override; the status falls back to the derived one.

Server emits &#x60;word.status_cleared&#x60;. No override present (or no row): &#x60;200&#x60; with the current status, no op. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = WordsApi()
val seq : kotlin.Long = 1358280 // kotlin.Long | JMdict sequence number of the entry.
try {
    val result : WordStatus = apiInstance.clearWordStatus(seq)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling WordsApi#clearWordStatus")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling WordsApi#clearWordStatus")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **seq** | **kotlin.Long**| JMdict sequence number of the entry. | |

### Return type

[**WordStatus**](WordStatus.md)

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

<a id="getWordStatus"></a>
# **getWordStatus**
> WordStatus getWordStatus(seq)

Word status of one entry.

An entry without a &#x60;word_status&#x60; row is reported as &#x60;status &#x3D; new&#x60; with every other property null. The entry need not exist in the dictionary.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = WordsApi()
val seq : kotlin.Long = 1358280 // kotlin.Long | JMdict sequence number of the entry.
try {
    val result : WordStatus = apiInstance.getWordStatus(seq)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling WordsApi#getWordStatus")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling WordsApi#getWordStatus")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **seq** | **kotlin.Long**| JMdict sequence number of the entry. | |

### Return type

[**WordStatus**](WordStatus.md)

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

<a id="listConfusables"></a>
# **listConfusables**
> ConfusablePage listConfusables(status, noteId, limit, cursor)

All confusable pairs (any note type).

Ordered by &#x60;updated_at&#x60; descending, then &#x60;(note_a_id, note_b_id, reason)&#x60;. Pairs with a deleted note are hidden; merged notes are resolved to survivors.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = WordsApi()
val status : ConfusableStatus = suggested // ConfusableStatus | Only pairs with this status. Default - all three.
val noteId : kotlin.String = 01931f40-1a2b-7c3d-8e4f-000000000a01 // kotlin.String | Only pairs involving this note.
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : ConfusablePage = apiInstance.listConfusables(status, noteId, limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling WordsApi#listConfusables")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling WordsApi#listConfusables")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **status** | [**ConfusableStatus**](.md)| Only pairs with this status. Default - all three. | [optional] [enum: suggested, confirmed, rejected] |
| **noteId** | **kotlin.String**| Only pairs involving this note. | [optional] |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**ConfusablePage**](ConfusablePage.md)

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

<a id="listHotKanji"></a>
# **listHotKanji**
> HotKanjiList listHotKanji(limit)

Hot kanji - recent failures rolled up by kanji.

Scores come from the shared core (hot-word score summed over every live vocab note whose entry contains the kanji, via &#x60;ref.entry_kanji&#x60;). Highest score first, then &#x60;literal&#x60;. Only kanji with a score above zero. Not paginated. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = WordsApi()
val limit : kotlin.Int = 20 // kotlin.Int | Maximum number of kanji.
try {
    val result : HotKanjiList = apiInstance.listHotKanji(limit)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling WordsApi#listHotKanji")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling WordsApi#listHotKanji")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **limit** | **kotlin.Int**| Maximum number of kanji. | [optional] [default to 20] |

### Return type

[**HotKanjiList**](HotKanjiList.md)

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

<a id="listHotWords"></a>
# **listHotWords**
> HotWordList listHotWords(limit)

Hot words - recent struggle, not lifetime.

Score from the shared core (failures over the last 14 days with decay, FSRS difficulty, lapse rate, relearning failures). Only live vocab notes with a score above zero; highest score first, then note id. Not paginated. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = WordsApi()
val limit : kotlin.Int = 20 // kotlin.Int | Maximum number of words.
try {
    val result : HotWordList = apiInstance.listHotWords(limit)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling WordsApi#listHotWords")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling WordsApi#listHotWords")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **limit** | **kotlin.Int**| Maximum number of words. | [optional] [default to 20] |

### Return type

[**HotWordList**](HotWordList.md)

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

<a id="listLeeches"></a>
# **listLeeches**
> CardPage listLeeches(limit, cursor)

Leech cards (lapses at or above &#x60;scheduler.leech_threshold&#x60;).

Live cards of live notes with &#x60;lapses &gt;&#x3D; scheduler.leech_threshold&#x60;, suspended ones included. Ordered by &#x60;lapses&#x60; descending, then card id. Covers every note type, not only vocab. Equivalent to &#x60;GET /cards?query&#x3D;tag:leech&amp;sort&#x3D;-lapses&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = WordsApi()
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : CardPage = apiInstance.listLeeches(limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling WordsApi#listLeeches")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling WordsApi#listLeeches")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**CardPage**](CardPage.md)

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

<a id="listWordConfusables"></a>
# **listWordConfusables**
> ConfusableList listWordConfusables(seq, status)

Confusable partners of the word&#39;s note.

Pairs involving the live vocab note of this entry, resolved through &#x60;merged_into_id&#x60;, pairs with a deleted note hidden. Ordered: &#x60;confirmed&#x60; first, then &#x60;suggested&#x60;; inside by &#x60;updated_at&#x60; descending. &#x60;rejected&#x60; pairs only with &#x60;status&#x3D;rejected&#x60;. No note for the entry: empty list. Not paginated. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = WordsApi()
val seq : kotlin.Long = 1358280 // kotlin.Long | JMdict sequence number of the entry.
val status : ConfusableStatus = confirmed // ConfusableStatus | Only pairs with this status. Default - `suggested` and `confirmed`.
try {
    val result : ConfusableList = apiInstance.listWordConfusables(seq, status)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling WordsApi#listWordConfusables")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling WordsApi#listWordConfusables")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **seq** | **kotlin.Long**| JMdict sequence number of the entry. | |
| **status** | [**ConfusableStatus**](.md)| Only pairs with this status. Default - &#x60;suggested&#x60; and &#x60;confirmed&#x60;. | [optional] [enum: suggested, confirmed, rejected] |

### Return type

[**ConfusableList**](ConfusableList.md)

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

<a id="setConfusable"></a>
# **setConfusable**
> Confusable setConfusable(noteAId, noteBId, reason, confusableSet)

Confirm or reject a suggested pair, or record a pair yourself.

Server emits &#x60;confusable.confirmed&#x60; or &#x60;confusable.rejected&#x60;. If the pair does not exist yet the op creates it (a hand-made pair is simply a confirmed one). Both notes must exist; merge losers are resolved to survivors first, and if both resolve to the same note the call is &#x60;422&#x60;. A user decision always beats a suggestion; between decisions the later clock wins. Rejected pairs are kept so they are not suggested again. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = WordsApi()
val noteAId : kotlin.String = 01931f40-1a2b-7c3d-8e4f-000000000a01 // kotlin.String | One note of the pair. The two ids may be given in either order; the server stores the smaller first.
val noteBId : kotlin.String = 01931f40-1a2b-7c3d-8e4f-000000000a02 // kotlin.String | The other note. Must differ from `note_a_id` (`422`).
val reason : ConfusableReason = look_alike // ConfusableReason | Why the two are confusable.
val confusableSet : ConfusableSet = {"status":"confirmed"} // ConfusableSet | 
try {
    val result : Confusable = apiInstance.setConfusable(noteAId, noteBId, reason, confusableSet)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling WordsApi#setConfusable")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling WordsApi#setConfusable")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **noteAId** | **kotlin.String**| One note of the pair. The two ids may be given in either order; the server stores the smaller first. | |
| **noteBId** | **kotlin.String**| The other note. Must differ from &#x60;note_a_id&#x60; (&#x60;422&#x60;). | |
| **reason** | [**ConfusableReason**](.md)| Why the two are confusable. | [enum: homophone, look_alike, meaning] |
| **confusableSet** | [**ConfusableSet**](ConfusableSet.md)|  | |

### Return type

[**Confusable**](Confusable.md)

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

<a id="setWordStatus"></a>
# **setWordStatus**
> WordStatus setWordStatus(seq, wordStatusSet)

Set an explicit status override (known, never-forget, blacklisted, ignored).

Server emits &#x60;word.status_set&#x60; with payload &#x60;origin &#x3D; manual&#x60; (stored as &#x60;override_origin&#x60;). &#x60;new&#x60; and &#x60;learning&#x60; cannot be set (they are derived): to return to the derived status use &#x60;DELETE&#x60;. The entry need not exist in the dictionary and need not have a note. Setting the status it already has still emits (bumps the clock). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = WordsApi()
val seq : kotlin.Long = 1358280 // kotlin.Long | JMdict sequence number of the entry.
val wordStatusSet : WordStatusSet = {"status":"blacklisted"} // WordStatusSet | 
try {
    val result : WordStatus = apiInstance.setWordStatus(seq, wordStatusSet)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling WordsApi#setWordStatus")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling WordsApi#setWordStatus")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **seq** | **kotlin.Long**| JMdict sequence number of the entry. | |
| **wordStatusSet** | [**WordStatusSet**](WordStatusSet.md)|  | |

### Return type

[**WordStatus**](WordStatus.md)

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

