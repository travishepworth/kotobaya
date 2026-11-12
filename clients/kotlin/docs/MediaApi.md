# MediaApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**deleteMedia**](MediaApi.md#deleteMedia) | **DELETE** /media/{media_id} | Delete a media, its deck and its parsed content. |
| [**getMedia**](MediaApi.md#getMedia) | **GET** /media/{media_id} | One media with its segments. |
| [**getMediaCoverage**](MediaApi.md#getMediaCoverage) | **GET** /media/{media_id}/coverage | Coverage of a media (or one segment) by your word statuses. |
| [**getSentence**](MediaApi.md#getSentence) | **GET** /sentences/{sentence_id} | One sentence with tokens and grammar spans. |
| [**importJpdbDeck**](MediaApi.md#importJpdbDeck) | **POST** /media/import/jpdb | Import a pre-parsed jpdb media deck (vocabulary with occurrence counts). |
| [**importJpdbHistory**](MediaApi.md#importJpdbHistory) | **POST** /import/jpdb/history | Import jpdb review history and word statuses from a jpdb export file. |
| [**importMedia**](MediaApi.md#importMedia) | **POST** /media/import | Import your own file(s), a URL or pasted text as media (worker job). |
| [**listMedia**](MediaApi.md#listMedia) | **GET** /media | List the media library. |
| [**listMediaSentences**](MediaApi.md#listMediaSentences) | **GET** /media/{media_id}/sentences | Sentences of a media, optionally only i+1 sentences. |
| [**listSentences**](MediaApi.md#listSentences) | **GET** /sentences | Search sentences across all media. |
| [**reparseMedia**](MediaApi.md#reparseMedia) | **POST** /media/{media_id}/reparse | Re-parse a media (after parse overrides or a dictionary refresh). |
| [**updateMedia**](MediaApi.md#updateMedia) | **PATCH** /media/{media_id} | Change a media&#39;s title, kind or cover. |


<a id="deleteMedia"></a>
# **deleteMedia**
> deleteMedia(mediaId)

Delete a media, its deck and its parsed content.

Server emits &#x60;media.deleted&#x60; and, as a consequence in the same transaction, &#x60;deck.deleted&#x60; for the media deck; then hard-deletes the media&#39;s segments, sentences, occurrences and detected grammar spans (SCHEMA.md 4.3). Notes mined from it, manual spans and reviews stay. Queued or running jobs for the media are cancelled. Already deleted: &#x60;204&#x60;, no op. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MediaApi()
val mediaId : kotlin.String = 01931f40-3000-7000-8000-000000000e01 // kotlin.String | Media id.
try {
    apiInstance.deleteMedia(mediaId)
} catch (e: ClientException) {
    println("4xx response calling MediaApi#deleteMedia")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MediaApi#deleteMedia")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **mediaId** | **kotlin.String**| Media id. | |

### Return type

null (empty response body)

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
 - **Accept**: application/problem+json

<a id="getMedia"></a>
# **getMedia**
> Media getMedia(mediaId, includeDeleted)

One media with its segments.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MediaApi()
val mediaId : kotlin.String = 01931f40-3000-7000-8000-000000000e01 // kotlin.String | Media id.
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
try {
    val result : Media = apiInstance.getMedia(mediaId, includeDeleted)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MediaApi#getMedia")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MediaApi#getMedia")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **mediaId** | **kotlin.String**| Media id. | |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |

### Return type

[**Media**](Media.md)

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

<a id="getMediaCoverage"></a>
# **getMediaCoverage**
> Coverage getMediaCoverage(mediaId, segmentId, hide)

Coverage of a media (or one segment) by your word statuses.

Computed at request time from &#x60;occurrence&#x60;, &#x60;media_segment&#x60; and &#x60;word_status&#x60;, so it always reflects the current statuses. Definitions are on the &#x60;Coverage&#x60; schema. With &#x60;segment_id&#x60; the numbers cover only that segment and &#x60;segments&#x60; has that one element. Media with &#x60;content_version &#x3D; 0&#x60; (not parsed yet) returns all-zero numbers. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MediaApi()
val mediaId : kotlin.String = 01931f40-3000-7000-8000-000000000e01 // kotlin.String | Media id.
val segmentId : kotlin.String = 01931f40-3100-7000-8000-000000000e11 // kotlin.String | Restrict to one segment of this media (`404` if it is not one).
val hide : kotlin.collections.Set<kotlin.String> = [names, particles, ignored] // kotlin.collections.Set<kotlin.String> | Classes left out of every coverage number, comma-separated. `names`, `particles`, `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`; `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide nothing. 
try {
    val result : Coverage = apiInstance.getMediaCoverage(mediaId, segmentId, hide)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MediaApi#getMediaCoverage")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MediaApi#getMediaCoverage")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **mediaId** | **kotlin.String**| Media id. | |
| **segmentId** | **kotlin.String**| Restrict to one segment of this media (&#x60;404&#x60; if it is not one). | [optional] |
| **hide** | [**kotlin.collections.Set&lt;kotlin.String&gt;**](kotlin.String.md)| Classes left out of every coverage number, comma-separated. &#x60;names&#x60;, &#x60;particles&#x60;, &#x60;auxiliary&#x60; &#x3D; entries whose &#x60;occurrence.pos_class&#x60; is &#x60;name&#x60;, &#x60;particle&#x60;, &#x60;auxiliary&#x60;; &#x60;ignored&#x60; &#x3D; entries whose word status is &#x60;blacklisted&#x60; or &#x60;ignored&#x60;; &#x60;unmapped&#x60; &#x3D; tokens that matched no dictionary entry. Default &#x60;ignored&#x60;. Pass an empty value (&#x60;hide&#x3D;&#x60;) to hide nothing.  | [optional] [enum: names, particles, auxiliary, ignored, unmapped] |

### Return type

[**Coverage**](Coverage.md)

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

<a id="getSentence"></a>
# **getSentence**
> Sentence getSentence(sentenceId)

One sentence with tokens and grammar spans.

&#x60;404&#x60; when the sentence does not exist (never imported, or purged with its media or by a re-parse).

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MediaApi()
val sentenceId : kotlin.String = 01931f40-5000-7000-8000-000000000701 // kotlin.String | Sentence id.
try {
    val result : Sentence = apiInstance.getSentence(sentenceId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MediaApi#getSentence")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MediaApi#getSentence")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **sentenceId** | **kotlin.String**| Sentence id. | |

### Return type

[**Sentence**](Sentence.md)

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

<a id="importJpdbDeck"></a>
# **importJpdbDeck**
> Job importJpdbDeck(jpdbDeckImportRequest)

Import a pre-parsed jpdb media deck (vocabulary with occurrence counts).

Enqueues a job of kind &#x60;jpdb-deck-import&#x60; (dedupe key &#x60;jpdb-deck-import:&lt;jpdb_id&gt;&#x60;) and returns it with &#x60;202&#x60;. If a live media with this &#x60;jpdb_id&#x60; exists the job updates it (same &#x60;media_id&#x60;, new content version); otherwise &#x60;media_id&#x60; is pre-allocated as in &#x60;POST /media/import&#x60;. The job emits, as server ops, &#x60;media.created&#x60; and &#x60;deck.created&#x60; (first import only), &#x60;media.content_published&#x60;, &#x60;deck.content_published&#x60;. The result has one segment of kind &#x60;whole&#x60; and no sentences. Vocabulary that maps to no JMdict entry is listed in the job result (&#x60;unmapped&#x60;), never silently dropped. An equivalent queued or running job is returned with &#x60;200&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MediaApi()
val jpdbDeckImportRequest : JpdbDeckImportRequest = {"deck":{"priority":5},"jpdb_id":"anime/5370","kind":"anime"} // JpdbDeckImportRequest | 
try {
    val result : Job = apiInstance.importJpdbDeck(jpdbDeckImportRequest)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MediaApi#importJpdbDeck")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MediaApi#importJpdbDeck")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **jpdbDeckImportRequest** | [**JpdbDeckImportRequest**](JpdbDeckImportRequest.md)|  | |

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

<a id="importJpdbHistory"></a>
# **importJpdbHistory**
> Job importJpdbHistory(jpdbHistoryImportRequest)

Import jpdb review history and word statuses from a jpdb export file.

Upload the export JSON with &#x60;PUT /blobs/{hash}&#x60; (kind &#x60;upload&#x60;), then call this. Enqueues a job of kind &#x60;jpdb-history-import&#x60; (dedupe key &#x60;jpdb-history-import&#x60;). The job emits, as server ops: &#x60;note.created&#x60; and &#x60;card.created&#x60; for vocabulary without a note, &#x60;review.logged&#x60; for every historical review (&#x60;import_source &#x3D; jpdb&#x60;, deterministic review ids derived from the export so that re-running imports nothing twice), and &#x60;word.status_set&#x60; (payload &#x60;origin &#x3D; jpdb_import&#x60;, stored as &#x60;override_origin&#x60;) for known, never-forget and blacklisted words. Records that cannot be mapped are counted and listed in the job result. An equivalent queued or running job is returned with &#x60;200&#x60;.  **Export arrays (C-2; spikes/jpdb-map/REPORT.md \&quot;Export structure\&quot;).** The export is one object whose array members are read as follows; any other member is ignored, a missing array counts as empty, and &#x60;from_anki&#x60; is ignored. | Array | Row | Becomes | |---|---|---| | &#x60;cards_vocabulary_jp_en&#x60;, &#x60;cards_vocabulary_en_jp&#x60; | &#x60;vid&#x60;, &#x60;spelling&#x60;, &#x60;reading&#x60;, &#x60;reviews[]&#x60; | vocab note (built-in Vocab type, &#x60;entry_seq&#x60; &#x3D; the mapped JMdict seq, &#x60;origin &#x3D; jpdb_import&#x60;) | | &#x60;cards_kanji_char_keyword&#x60; | &#x60;character&#x60;, &#x60;reviews[]&#x60; | kanji note, card kind &#x60;keyword&#x60; (kanji → keyword) | | &#x60;cards_kanji_keyword_char&#x60; | &#x60;character&#x60;, &#x60;reviews[]&#x60; | kanji note, card kind &#x60;writing&#x60; (keyword → kanji) |  **Kanji rows.** A row whose &#x60;character&#x60; is exactly one Unicode code point maps to the live note with that &#x60;kanji_literal&#x60; if one exists (counted &#x60;kanji_notes_existing&#x60;, once per note), else to a new note: &#x60;note.created&#x60; with &#x60;note_type_id&#x60; &#x3D; the built-in Kanji type (&#x60;00000000-0000-7000-8000-000000000102&#x60;, base type &#x60;kanji&#x60;), &#x60;kanji_literal&#x60; &#x3D; &#x60;character&#x60;, &#x60;fields&#x60; &#x3D; &#x60;{\&quot;literal\&quot;: character}&#x60; (no &#x60;keyword&#x60;, &#x60;readings&#x60;, &#x60;notes&#x60;: the renderer fills them from &#x60;ref.kanji&#x60;, &#x60;RenderInput.kanji&#x60;), &#x60;origin &#x3D; jpdb_import&#x60;. Both arrays naming the same character share one note. The job then ensures a live card of the row&#39;s kind (&#x60;keyword&#x60; or &#x60;writing&#x60;) - only that kind, never the note type&#39;s other default kinds. Any other &#x60;character&#x60; is listed under &#x60;kanji_unmapped&#x60;. A character missing from &#x60;ref.kanji&#x60; is still imported.  **Grades** (each element of &#x60;reviews[]&#x60;; &#x60;timestamp&#x60; is Unix seconds UTC → &#x60;reviewed_at&#x60; &#x3D; timestamp × 1000, &#x60;utc_offset_min &#x3D; null&#x60;, &#x60;duration_ms &#x3D; null&#x60;, &#x60;review_type &#x3D; scheduled&#x60;, &#x60;import_source &#x3D; jpdb&#x60;): | &#x60;grade&#x60; | Effect | |---|---| | &#x60;nothing&#x60; | &#x60;review.logged&#x60; rating 1, button &#x60;nothing&#x60; | | &#x60;something&#x60; | rating 1, button &#x60;something&#x60; | | &#x60;hard&#x60; | rating 2, button &#x60;hard&#x60; (FSRS Hard; ADR-008) | | &#x60;okay&#x60; | rating 3, button &#x60;okay&#x60; | | &#x60;easy&#x60; | rating 4, button &#x60;easy&#x60; | | &#x60;pass&#x60; | rating 3, button &#x60;null&#x60; | | &#x60;fail&#x60; | rating 1, button &#x60;null&#x60; | | &#x60;unknown&#x60; | no review: the row&#39;s note and card are created (as for any mapped row), nothing else | | &#x60;known&#x60; | no review. Vocab: &#x60;word.status_set&#x60; status &#x60;known&#x60;, &#x60;origin &#x3D; jpdb_import&#x60; (if &#x60;import_word_status&#x60;). Kanji: nothing beyond the note and card | | anything else | nothing; counted &#x60;reviews_unknown_grade&#x60; |  A mapped vocab row gets its note and card iff it has at least one event other than &#x60;known&#x60; (&#x60;vocabulary_carded&#x60;); one whose events are all &#x60;known&#x60; gets only the word status (&#x60;vocabulary_status_only&#x60;). Every mapped kanji row gets its note and card (&#x60;kanji_carded&#x60;), even if its only events are &#x60;known&#x60; (kanji have no word status). With &#x60;import_reviews &#x3D; false&#x60; nothing but word statuses is written: rows are bucketed by the same rules (&#x60;*_carded&#x60; then means \&quot;would get a note and card\&quot;), rating events count as &#x60;reviews_skipped&#x60;, status events as usual.  **Counting.** Every input record - each row of the four arrays and each element of its &#x60;reviews[]&#x60; - is counted in exactly one bucket of &#x60;JobResultJpdbHistory&#x60; (its description gives the three sums that must hold). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MediaApi()
val jpdbHistoryImportRequest : JpdbHistoryImportRequest = {"blob_hash":"4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a"} // JpdbHistoryImportRequest | 
try {
    val result : Job = apiInstance.importJpdbHistory(jpdbHistoryImportRequest)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MediaApi#importJpdbHistory")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MediaApi#importJpdbHistory")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **jpdbHistoryImportRequest** | [**JpdbHistoryImportRequest**](JpdbHistoryImportRequest.md)|  | |

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

<a id="importMedia"></a>
# **importMedia**
> Job importMedia(mediaImportRequest)

Import your own file(s), a URL or pasted text as media (worker job).

Enqueues a job of kind &#x60;media-import&#x60; and returns it with &#x60;202&#x60;. Files are uploaded first with &#x60;PUT /blobs/{hash}&#x60; (kind &#x60;upload&#x60;) and referenced by hash; there is no multipart variant.  - &#x60;source: file&#x60; - &#x60;files&#x60; required (1 to 500). Each file becomes one segment in array   order (an EPUB becomes one segment per chapter, in book order). Every &#x60;blob_hash&#x60; must   exist (&#x60;422&#x60; naming the element otherwise). - &#x60;source: url&#x60; - &#x60;url&#x60; required (http/https); fetched by the worker with readability   extraction; one segment. - &#x60;source: paste&#x60; - &#x60;text&#x60; required (1 to 1,000,000 characters); one segment. - New media: &#x60;title&#x60; and &#x60;kind&#x60; required. The server pre-allocates &#x60;media_id&#x60; (returned   in &#x60;job.payload.media_id&#x60;); the job creates the &#x60;media&#x60; row, its deck (&#x60;deck.id&#x60; &#x3D;   &#x60;media_id&#x60;) and all content, emitting as server ops &#x60;media.created&#x60;, &#x60;deck.created&#x60;,   &#x60;media.content_published&#x60;, &#x60;deck.content_published&#x60;. &#x60;GET /media/{media_id}&#x60; is &#x60;404&#x60;   until the job has created the row. - Existing media: pass &#x60;media_id&#x60; (must be live, with &#x60;source&#x60; equal to this request&#39;s);   the new segments are appended after the existing ones and a new content version is   published. &#x60;title&#x60;/&#x60;kind&#x60;/&#x60;deck&#x60; must then be absent. - Dedupe key &#x60;media-import:&lt;media_id&gt;&#x60;: a second import into the same media while one is   queued or running is &#x60;409 already_exists&#x60; (&#x60;existing_id&#x60; &#x3D; the job id). - **Idempotent retry: &#x60;import_id&#x60;.** Optional client-generated UUIDv7, minted once per   user action and reused on every retry; it becomes the job&#39;s &#x60;id&#x60;, and for new media   also the new &#x60;media_id&#x60; (and so the deck id). If a job with this id already exists   (any status), or - for new media - a &#x60;media&#x60; row with this id exists (live or deleted),   the server enqueues nothing and answers &#x60;200&#x60; with that job (or, if the job row is   gone, the most recent &#x60;media-import&#x60; job for that media; &#x60;404&#x60; if none), whatever the   rest of the body says. Without &#x60;import_id&#x60; a retry after a lost &#x60;202&#x60; imports twice   (a second media and deck); clients that may retry MUST send it. An &#x60;import_id&#x60; used by   a job of another kind is &#x60;409 already_exists&#x60; (&#x60;existing_id&#x60; &#x3D; that job). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MediaApi()
val mediaImportRequest : MediaImportRequest = {"files":[{"blob_hash":"fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9","filename":"ep03.srt"}],"media_id":"01931f40-3000-7000-8000-000000000e01","source":"file"} // MediaImportRequest | 
try {
    val result : Job = apiInstance.importMedia(mediaImportRequest)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MediaApi#importMedia")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MediaApi#importMedia")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **mediaImportRequest** | [**MediaImportRequest**](MediaImportRequest.md)|  | |

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

<a id="listMedia"></a>
# **listMedia**
> MediaPage listMedia(kind, q, includeDeleted, limit, cursor)

List the media library.

Ordered by &#x60;created_at&#x60; descending, then &#x60;id&#x60; descending.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MediaApi()
val kind : MediaKind = anime // MediaKind | Only media of this kind.
val q : kotlin.String = フリーレン // kotlin.String | Case-insensitive substring of the title.
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : MediaPage = apiInstance.listMedia(kind, q, includeDeleted, limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MediaApi#listMedia")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MediaApi#listMedia")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **kind** | [**MediaKind**](.md)| Only media of this kind. | [optional] [enum: anime, novel, vn, manga, web, other] |
| **q** | **kotlin.String**| Case-insensitive substring of the title. | [optional] |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**MediaPage**](MediaPage.md)

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

<a id="listMediaSentences"></a>
# **listMediaSentences**
> SentencePage listMediaSentences(mediaId, filter, segmentId, q, entrySeq, grammarPointId, limit, cursor)

Sentences of a media, optionally only i+1 sentences.

Same as &#x60;GET /sentences&#x60; with &#x60;media_id&#x60; fixed. Ordered by segment &#x60;ordinal&#x60;, then sentence &#x60;ordinal&#x60;. jpdb-imported media have no sentences (empty page). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MediaApi()
val mediaId : kotlin.String = 01931f40-3000-7000-8000-000000000e01 // kotlin.String | Media id.
val filter : kotlin.String = i+1 // kotlin.String | `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. 
val segmentId : kotlin.String = 01931f40-3100-7000-8000-000000000e11 // kotlin.String | Only sentences of this segment.
val q : kotlin.String = 食べ // kotlin.String | Substring of the sentence text (exact characters, no normalisation).
val entrySeq : kotlin.Long = 1358280 // kotlin.Long | Only sentences with a token mapped to this entry.
val grammarPointId : kotlin.String = 01931f40-4000-7000-8000-000000000901 // kotlin.String | Only sentences with a live span of this grammar point.
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : SentencePage = apiInstance.listMediaSentences(mediaId, filter, segmentId, q, entrySeq, grammarPointId, limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MediaApi#listMediaSentences")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MediaApi#listMediaSentences")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **mediaId** | **kotlin.String**| Media id. | |
| **filter** | **kotlin.String**| &#x60;all&#x60; (default); &#x60;i+1&#x60; &#x3D; sentences with exactly one unknown word; &#x60;i+0&#x60; &#x3D; none. \&quot;Unknown word\&quot; &#x3D; a distinct entry among the sentence&#39;s tokens whose effective word status is &#x60;new&#x60;; tokens of class &#x60;particle&#x60;, &#x60;auxiliary&#x60;, &#x60;name&#x60;, unmapped tokens and entries with status &#x60;blacklisted&#x60;/&#x60;ignored&#x60; are not counted. Because an unencoded &#x60;+&#x60; in a query string decodes to a space, servers MUST also accept &#x60;i 1&#x60; and &#x60;i 0&#x60;.  | [optional] [enum: all, i+0, i+1] |
| **segmentId** | **kotlin.String**| Only sentences of this segment. | [optional] |
| **q** | **kotlin.String**| Substring of the sentence text (exact characters, no normalisation). | [optional] |
| **entrySeq** | **kotlin.Long**| Only sentences with a token mapped to this entry. | [optional] |
| **grammarPointId** | **kotlin.String**| Only sentences with a live span of this grammar point. | [optional] |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**SentencePage**](SentencePage.md)

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

<a id="listSentences"></a>
# **listSentences**
> SentencePage listSentences(mediaId, filter, segmentId, q, entrySeq, grammarPointId, limit, cursor)

Search sentences across all media.

All filters combine with AND. Ordered by media &#x60;created_at&#x60; descending, media id, segment &#x60;ordinal&#x60;, sentence &#x60;ordinal&#x60;. Sentences of deleted media do not exist any more and are never returned. LLM-generated sentences (&#x60;origin &#x3D; generated&#x60;) are included and must be labelled by clients. Each sentence carries its live grammar spans (detected and manual). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MediaApi()
val mediaId : kotlin.String = 01931f40-3000-7000-8000-000000000e01 // kotlin.String | Only sentences of this media.
val filter : kotlin.String = i+1 // kotlin.String | `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. 
val segmentId : kotlin.String = 01931f40-3100-7000-8000-000000000e11 // kotlin.String | Only sentences of this segment.
val q : kotlin.String = 食べ // kotlin.String | Substring of the sentence text (exact characters, no normalisation).
val entrySeq : kotlin.Long = 1358280 // kotlin.Long | Only sentences with a token mapped to this entry.
val grammarPointId : kotlin.String = 01931f40-4000-7000-8000-000000000901 // kotlin.String | Only sentences with a live span of this grammar point.
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : SentencePage = apiInstance.listSentences(mediaId, filter, segmentId, q, entrySeq, grammarPointId, limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MediaApi#listSentences")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MediaApi#listSentences")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **mediaId** | **kotlin.String**| Only sentences of this media. | [optional] |
| **filter** | **kotlin.String**| &#x60;all&#x60; (default); &#x60;i+1&#x60; &#x3D; sentences with exactly one unknown word; &#x60;i+0&#x60; &#x3D; none. \&quot;Unknown word\&quot; &#x3D; a distinct entry among the sentence&#39;s tokens whose effective word status is &#x60;new&#x60;; tokens of class &#x60;particle&#x60;, &#x60;auxiliary&#x60;, &#x60;name&#x60;, unmapped tokens and entries with status &#x60;blacklisted&#x60;/&#x60;ignored&#x60; are not counted. Because an unencoded &#x60;+&#x60; in a query string decodes to a space, servers MUST also accept &#x60;i 1&#x60; and &#x60;i 0&#x60;.  | [optional] [enum: all, i+0, i+1] |
| **segmentId** | **kotlin.String**| Only sentences of this segment. | [optional] |
| **q** | **kotlin.String**| Substring of the sentence text (exact characters, no normalisation). | [optional] |
| **entrySeq** | **kotlin.Long**| Only sentences with a token mapped to this entry. | [optional] |
| **grammarPointId** | **kotlin.String**| Only sentences with a live span of this grammar point. | [optional] |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**SentencePage**](SentencePage.md)

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

<a id="reparseMedia"></a>
# **reparseMedia**
> Job reparseMedia(mediaId)

Re-parse a media (after parse overrides or a dictionary refresh).

Enqueues a job of kind &#x60;media-parse&#x60; (dedupe key &#x60;media-parse:&lt;media_id&gt;&#x60;); an equivalent queued or running job is returned with &#x60;200&#x60;. The job keeps segment and sentence ids as SCHEMA.md requires and makes the server emit &#x60;media.content_published&#x60; and &#x60;deck.content_published&#x60;. Media with &#x60;source &#x3D; jpdb&#x60; has nothing to parse: &#x60;409 not_applicable&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MediaApi()
val mediaId : kotlin.String = 01931f40-3000-7000-8000-000000000e01 // kotlin.String | Media id.
try {
    val result : Job = apiInstance.reparseMedia(mediaId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MediaApi#reparseMedia")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MediaApi#reparseMedia")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **mediaId** | **kotlin.String**| Media id. | |

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

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="updateMedia"></a>
# **updateMedia**
> Media updateMedia(mediaId, mediaPatch)

Change a media&#39;s title, kind or cover.

Server emits one &#x60;media.updated&#x60; with the registers present (&#x60;title&#x60;, &#x60;kind&#x60;, &#x60;cover_blob_hash&#x60;). &#x60;cover_blob_hash&#x60; must be an existing blob of kind &#x60;image&#x60;, or &#x60;null&#x60; to remove the cover. The media deck&#39;s name is not changed (patch the deck). Empty body: &#x60;422&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = MediaApi()
val mediaId : kotlin.String = 01931f40-3000-7000-8000-000000000e01 // kotlin.String | Media id.
val mediaPatch : MediaPatch = {"kind":"anime","title":"葬送のフリーレン (S1)"} // MediaPatch | 
try {
    val result : Media = apiInstance.updateMedia(mediaId, mediaPatch)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MediaApi#updateMedia")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MediaApi#updateMedia")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **mediaId** | **kotlin.String**| Media id. | |
| **mediaPatch** | [**MediaPatch**](MediaPatch.md)|  | |

### Return type

[**Media**](Media.md)

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

