# MediaApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**deleteMedia**](MediaApi.md#deletemedia) | **DELETE** /media/{media_id} | Delete a media, its deck and its parsed content. |
| [**getMedia**](MediaApi.md#getmedia) | **GET** /media/{media_id} | One media with its segments. |
| [**getMediaCoverage**](MediaApi.md#getmediacoverage) | **GET** /media/{media_id}/coverage | Coverage of a media (or one segment) by your word statuses. |
| [**getSentence**](MediaApi.md#getsentence) | **GET** /sentences/{sentence_id} | One sentence with tokens and grammar spans. |
| [**importJpdbDeck**](MediaApi.md#importjpdbdeck) | **POST** /media/import/jpdb | Import a pre-parsed jpdb media deck (vocabulary with occurrence counts). |
| [**importJpdbHistory**](MediaApi.md#importjpdbhistory) | **POST** /import/jpdb/history | Import jpdb review history and word statuses from a jpdb export file. |
| [**importMedia**](MediaApi.md#importmedia) | **POST** /media/import | Import your own file(s), a URL or pasted text as media (worker job). |
| [**listMedia**](MediaApi.md#listmedia) | **GET** /media | List the media library. |
| [**listMediaSentences**](MediaApi.md#listmediasentences) | **GET** /media/{media_id}/sentences | Sentences of a media, optionally only i+1 sentences. |
| [**listSentences**](MediaApi.md#listsentences) | **GET** /sentences | Search sentences across all media. |
| [**reparseMedia**](MediaApi.md#reparsemedia) | **POST** /media/{media_id}/reparse | Re-parse a media (after parse overrides or a dictionary refresh). |
| [**updateMedia**](MediaApi.md#updatemedia) | **PATCH** /media/{media_id} | Change a media\&#39;s title, kind or cover. |



## deleteMedia

> deleteMedia(mediaId)

Delete a media, its deck and its parsed content.

Server emits &#x60;media.deleted&#x60; and, as a consequence in the same transaction, &#x60;deck.deleted&#x60; for the media deck; then hard-deletes the media\&#39;s segments, sentences, occurrences and detected grammar spans (SCHEMA.md 4.3). Notes mined from it, manual spans and reviews stay. Queued or running jobs for the media are cancelled. Already deleted: &#x60;204&#x60;, no op. 

### Example

```ts
import {
  Configuration,
  MediaApi,
} from '@kotobaya/api-client';
import type { DeleteMediaRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MediaApi(config);

  const body = {
    // string | Media id.
    mediaId: 01931f40-3000-7000-8000-000000000e01,
  } satisfies DeleteMediaRequest;

  try {
    const data = await api.deleteMedia(body);
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
| **mediaId** | `string` | Media id. | [Defaults to `undefined`] |

### Return type

`void` (Empty response body)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Deleted. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getMedia

> Media getMedia(mediaId, includeDeleted)

One media with its segments.

### Example

```ts
import {
  Configuration,
  MediaApi,
} from '@kotobaya/api-client';
import type { GetMediaRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MediaApi(config);

  const body = {
    // string | Media id.
    mediaId: 01931f40-3000-7000-8000-000000000e01,
    // boolean | Also return tombstoned rows (`deleted_at` set). (optional)
    includeDeleted: false,
  } satisfies GetMediaRequest;

  try {
    const data = await api.getMedia(body);
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
| **mediaId** | `string` | Media id. | [Defaults to `undefined`] |
| **includeDeleted** | `boolean` | Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [Optional] [Defaults to `false`] |

### Return type

[**Media**](Media.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The media. |  -  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getMediaCoverage

> Coverage getMediaCoverage(mediaId, segmentId, hide)

Coverage of a media (or one segment) by your word statuses.

Computed at request time from &#x60;occurrence&#x60;, &#x60;media_segment&#x60; and &#x60;word_status&#x60;, so it always reflects the current statuses. Definitions are on the &#x60;Coverage&#x60; schema. With &#x60;segment_id&#x60; the numbers cover only that segment and &#x60;segments&#x60; has that one element. Media with &#x60;content_version &#x3D; 0&#x60; (not parsed yet) returns all-zero numbers. 

### Example

```ts
import {
  Configuration,
  MediaApi,
} from '@kotobaya/api-client';
import type { GetMediaCoverageRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MediaApi(config);

  const body = {
    // string | Media id.
    mediaId: 01931f40-3000-7000-8000-000000000e01,
    // string | Restrict to one segment of this media (`404` if it is not one). (optional)
    segmentId: 01931f40-3100-7000-8000-000000000e11,
    // Set<'names' | 'particles' | 'auxiliary' | 'ignored' | 'unmapped'> | Classes left out of every coverage number, comma-separated. `names`, `particles`, `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`; `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide nothing.  (optional)
    hide: [names, particles, ignored],
  } satisfies GetMediaCoverageRequest;

  try {
    const data = await api.getMediaCoverage(body);
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
| **mediaId** | `string` | Media id. | [Defaults to `undefined`] |
| **segmentId** | `string` | Restrict to one segment of this media (&#x60;404&#x60; if it is not one). | [Optional] [Defaults to `undefined`] |
| **hide** | `names`, `particles`, `auxiliary`, `ignored`, `unmapped` | Classes left out of every coverage number, comma-separated. &#x60;names&#x60;, &#x60;particles&#x60;, &#x60;auxiliary&#x60; &#x3D; entries whose &#x60;occurrence.pos_class&#x60; is &#x60;name&#x60;, &#x60;particle&#x60;, &#x60;auxiliary&#x60;; &#x60;ignored&#x60; &#x3D; entries whose word status is &#x60;blacklisted&#x60; or &#x60;ignored&#x60;; &#x60;unmapped&#x60; &#x3D; tokens that matched no dictionary entry. Default &#x60;ignored&#x60;. Pass an empty value (&#x60;hide&#x3D;&#x60;) to hide nothing.  | [Optional] [Enum: names, particles, auxiliary, ignored, unmapped] |

### Return type

[**Coverage**](Coverage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Coverage numbers. |  -  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getSentence

> Sentence getSentence(sentenceId)

One sentence with tokens and grammar spans.

&#x60;404&#x60; when the sentence does not exist (never imported, or purged with its media or by a re-parse).

### Example

```ts
import {
  Configuration,
  MediaApi,
} from '@kotobaya/api-client';
import type { GetSentenceRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MediaApi(config);

  const body = {
    // string | Sentence id.
    sentenceId: 01931f40-5000-7000-8000-000000000701,
  } satisfies GetSentenceRequest;

  try {
    const data = await api.getSentence(body);
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
| **sentenceId** | `string` | Sentence id. | [Defaults to `undefined`] |

### Return type

[**Sentence**](Sentence.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The sentence. |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## importJpdbDeck

> Job importJpdbDeck(jpdbDeckImportRequest)

Import a pre-parsed jpdb media deck (vocabulary with occurrence counts).

Enqueues a job of kind &#x60;jpdb-deck-import&#x60; (dedupe key &#x60;jpdb-deck-import:&lt;jpdb_id&gt;&#x60;) and returns it with &#x60;202&#x60;. If a live media with this &#x60;jpdb_id&#x60; exists the job updates it (same &#x60;media_id&#x60;, new content version); otherwise &#x60;media_id&#x60; is pre-allocated as in &#x60;POST /media/import&#x60;. The job emits, as server ops, &#x60;media.created&#x60; and &#x60;deck.created&#x60; (first import only), &#x60;media.content_published&#x60;, &#x60;deck.content_published&#x60;. The result has one segment of kind &#x60;whole&#x60; and no sentences. Vocabulary that maps to no JMdict entry is listed in the job result (&#x60;unmapped&#x60;), never silently dropped. An equivalent queued or running job is returned with &#x60;200&#x60;. 

### Example

```ts
import {
  Configuration,
  MediaApi,
} from '@kotobaya/api-client';
import type { ImportJpdbDeckRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MediaApi(config);

  const body = {
    // JpdbDeckImportRequest
    jpdbDeckImportRequest: {"deck":{"priority":5},"jpdb_id":"anime/5370","kind":"anime"},
  } satisfies ImportJpdbDeckRequest;

  try {
    const data = await api.importJpdbDeck(body);
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
| **jpdbDeckImportRequest** | [JpdbDeckImportRequest](JpdbDeckImportRequest.md) |  | |

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
| **200** | An equivalent job is already queued or running. |  -  |
| **202** | Import job queued. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## importJpdbHistory

> Job importJpdbHistory(jpdbHistoryImportRequest)

Import jpdb review history and word statuses from a jpdb export file.

Upload the export JSON with &#x60;PUT /blobs/{hash}&#x60; (kind &#x60;upload&#x60;), then call this. Enqueues a job of kind &#x60;jpdb-history-import&#x60; (dedupe key &#x60;jpdb-history-import&#x60;). The job emits, as server ops: &#x60;note.created&#x60; and &#x60;card.created&#x60; for vocabulary and kanji without a note or card, &#x60;review.logged&#x60; for every historical review (&#x60;import_source &#x3D; jpdb&#x60;, deterministic review ids, see \&quot;Review ids\&quot;, so that re-running imports nothing twice), and &#x60;word.status_set&#x60; (payload &#x60;origin &#x3D; jpdb_import&#x60;, stored as &#x60;override_origin&#x60;) for known-only words. The observed export carries no never-forget or blacklist data, so none is written. Records that cannot be mapped are counted and listed in the job result. An equivalent queued or running job is returned with &#x60;200&#x60;. Mapping rules follow ADR-008 and ADR-009 (C-4).  **Export arrays (C-2, C-4; spikes/jpdb-map/REPORT.md \&quot;Export structure\&quot;).** The export is one object whose array members are read as follows; any other member (top level, row or event) is ignored, a missing or non-array member counts as empty, and &#x60;from_anki&#x60; is accepted with any value (&#x60;true&#x60; included) and ignored. | Array | Row | Becomes | |---|---|---| | &#x60;cards_vocabulary_jp_en&#x60; | &#x60;vid&#x60;, &#x60;spelling&#x60;, &#x60;reading&#x60;, &#x60;reviews[]&#x60; | vocab note (built-in Vocab type &#x60;00000000-0000-7000-8000-000000000101&#x60;, &#x60;entry_seq&#x60; &#x3D; the mapped JMdict seq, &#x60;origin &#x3D; jpdb_import&#x60;) with exactly one card, kind &#x60;meaning&#x60; (JP → EN) | | &#x60;cards_vocabulary_en_jp&#x60; | same | nothing: counted &#x60;vocabulary_en_jp_ignored&#x60;, its events &#x60;reviews_skipped&#x60;; no note, card, status or review (ADR-009(1): no production cards) | | &#x60;cards_kanji_char_keyword&#x60; | &#x60;character&#x60;, &#x60;reviews[]&#x60;, optional &#x60;keyword&#x60; | kanji note, card kind &#x60;keyword&#x60; (kanji → keyword) | | &#x60;cards_kanji_keyword_char&#x60; | &#x60;character&#x60;, &#x60;reviews[]&#x60;, optional &#x60;keyword&#x60; | kanji note, card kind &#x60;writing&#x60; (keyword → kanji), created **suspended** |  Tolerated drift: a row whose &#x60;reviews&#x60; is missing or not an array has zero events; a vocab row with a missing or non-integer &#x60;vid&#x60; is mapped by text only and, if unmapped, listed with &#x60;jpdb_vid &#x3D; 0&#x60;; a missing &#x60;spelling&#x60; / &#x60;reading&#x60; reads as &#x60;\&quot;\&quot;&#x60;. An element of &#x60;reviews[]&#x60; that is not an object with an integer &#x60;timestamp&#x60; and a string &#x60;grade&#x60; is *malformed* (counted &#x60;reviews_unknown_grade&#x60;).  **Vocab rows and groups.** Each &#x60;cards_vocabulary_jp_en&#x60; row is mapped to a JMdict seq (vid lookup first; spikes/jpdb-map/REPORT.md \&quot;Mapping\&quot;) or listed under &#x60;unmapped&#x60;. Mapped rows are grouped by seq: rows sharing a seq (about 260 vids repeat in 2-3 rows with different spellings; two vids mapping to one seq are also one group) form **one group** &#x3D; one note and one &#x60;meaning&#x60; card, whose history is the union of the rows\&#39; events in ascending &#x60;timestamp&#x60; order (ties: array order, row order, element order). Within a group, an event with the same &#x60;timestamp&#x60; and &#x60;grade&#x60; as an earlier one is an **exact duplicate**: dropped and counted &#x60;reviews_duplicate_dropped&#x60; (malformed events are never duplicates). The group\&#39;s kept, well-formed events decide its fate: - all of them are &#x60;known&#x60; and there is at least one: **status-only** (ADR-009(3)). No   note or card. If &#x60;import_word_status&#x60;, one &#x60;word.status_set&#x60; status &#x60;known&#x60;, &#x60;origin &#x3D;   jpdb_import&#x60; for the seq, unless the word already has a live override (any status,   any origin; then nothing is written, so a re-run never overwrites the user\&#39;s choice).   Every row of the group counts &#x60;vocabulary_status_only&#x60;. - otherwise (at least one non-&#x60;known&#x60; event, or no well-formed events at all):   **carded**. Every row counts &#x60;vocabulary_carded&#x60;. The group maps to the live note with   that &#x60;entry_seq&#x60; if one exists (&#x60;notes_existing&#x60;, once per seq), else &#x60;note.created&#x60;   (&#x60;notes_created&#x60;). The job then ensures a live card of kind &#x60;meaning&#x60; on it   (&#x60;card.created&#x60;, &#x60;cards_created&#x60;, only if none exists) - only that kind, never the   Vocab type\&#39;s other default kinds (&#x60;reading&#x60;, &#x60;production&#x60;); existing cards of other   kinds are left alone and get no history. A &#x60;known&#x60; event of a carded group writes   nothing (FSRS schedules from the reviews; no override). So &#x60;notes_created + notes_existing&#x60; &#x3D; distinct seqs of carded groups, and &#x60;cards_created &lt;&#x3D; notes_created + notes_existing&#x60; (about equal to &#x60;notes_created&#x60;).  **Kanji rows and groups.** A row whose &#x60;character&#x60; is exactly one Unicode code point is mapped; any other is listed under &#x60;kanji_unmapped&#x60;. Mapped rows are grouped by (&#x60;character&#x60;, card kind); a group\&#39;s events are merged and de-duplicated exactly as for vocab. The character maps to the live note with that &#x60;kanji_literal&#x60; if one exists (counted &#x60;kanji_notes_existing&#x60;, once per character), else to a new note: &#x60;note.created&#x60; with &#x60;note_type_id&#x60; &#x3D; the built-in Kanji type (&#x60;00000000-0000-7000-8000-000000000102&#x60;, base type &#x60;kanji&#x60;), &#x60;kanji_literal&#x60; &#x3D; &#x60;character&#x60;, &#x60;fields&#x60; &#x3D; &#x60;{\&quot;literal\&quot;: character}&#x60; plus &#x60;\&quot;keyword\&quot;: &lt;text&gt;&#x60; when a row of that character carries a &#x60;keyword&#x60; member that is a non-empty string after trimming (ADR-009(4); the first such row in array order &#x60;cards_kanji_char_keyword&#x60;, &#x60;cards_kanji_keyword_char&#x60;, then row order; the observed export has no &#x60;keyword&#x60; member, so it is normally absent and the renderer fills it from &#x60;ref.kanji&#x60;; user-editable either way). &#x60;readings&#x60; and &#x60;notes&#x60; are never written. An existing note\&#39;s fields are never changed. &#x60;origin &#x3D; jpdb_import&#x60;. Both arrays naming the same character share one note. The job then ensures a live card of the group\&#39;s kind (&#x60;keyword&#x60; or &#x60;writing&#x60;) - only that kind, never the note type\&#39;s other default kinds. A card is created **with &#x60;suspended &#x3D; true&#x60; in its &#x60;card.created&#x60; payload** (no separate &#x60;card.suspended&#x60; op; counted &#x60;kanji_cards_suspended&#x60;) when its kind is &#x60;writing&#x60; (ADR-009(1): preserved with its history, never shown unless the user unsuspends it) or when the group is known-only (all kept well-formed events &#x60;known&#x60;, at least one; ADR-009(3)). A card that already exists keeps its &#x60;suspended&#x60; state (a re-run never re-suspends a card the user unsuspended). Every mapped kanji row counts &#x60;kanji_carded&#x60;, known-only ones included (kanji have no word status). A character missing from &#x60;ref.kanji&#x60; is still imported.  **Grades** (each kept, well-formed event of a carded vocab group or a kanji group; &#x60;timestamp&#x60; is Unix seconds UTC → &#x60;reviewed_at&#x60; &#x3D; timestamp × 1000, &#x60;utc_offset_min &#x3D; null&#x60;, &#x60;duration_ms &#x3D; null&#x60;, &#x60;review_type &#x3D; scheduled&#x60;, &#x60;import_source &#x3D; jpdb&#x60;; grades match exactly, case-sensitive): | &#x60;grade&#x60; | Effect | |---|---| | &#x60;nothing&#x60; | &#x60;review.logged&#x60; rating 1, button &#x60;nothing&#x60; | | &#x60;something&#x60; | rating 1, button &#x60;something&#x60; | | &#x60;hard&#x60; | rating 2, button &#x60;hard&#x60; (FSRS Hard; ADR-008) | | &#x60;okay&#x60; | rating 3, button &#x60;okay&#x60; | | &#x60;easy&#x60; | rating 4, button &#x60;easy&#x60; | | &#x60;pass&#x60; | rating 3, button &#x60;null&#x60; | | &#x60;fail&#x60; | rating 1, button &#x60;null&#x60; | | &#x60;unknown&#x60; | no review; the card\&#39;s creation is all it means. Counted &#x60;status_events_unknown&#x60; | | &#x60;known&#x60; | no review. Status-only vocab group: the word status above. Carded vocab group or any kanji group: nothing (a known-only kanji card is created suspended). Counted &#x60;status_events_known&#x60; | | anything else | nothing; counted &#x60;reviews_unknown_grade&#x60; |  **Review ids.** Each rating event gets &#x60;id&#x60; &#x3D; a UUIDv7 whose 48-bit timestamp is &#x60;reviewed_at&#x60; and whose other bits come from &#x60;h &#x3D; SHA-256(UTF-8(name))&#x60;, &#x60;name &#x3D; \&quot;jpdb-review/v1|\&quot; + key + \&quot;|\&quot; + timestamp + \&quot;|\&quot; + grade + \&quot;|\&quot; + occ&#x60;: &#x60;key&#x60; is &#x60;\&quot;v\&quot; + vid&#x60; (decimal, the event\&#39;s own row\&#39;s vid; &#x60;\&quot;v0\&quot;&#x60; if absent) for vocab, &#x60;\&quot;k:keyword:\&quot; + character&#x60; or &#x60;\&quot;k:writing:\&quot; + character&#x60; for kanji; &#x60;timestamp&#x60; is the export\&#39;s decimal seconds; &#x60;occ&#x60; is the 0-based index of the event among the group\&#39;s kept events with the same &#x60;timestamp&#x60;, in group order. Bytes: &#x60;b[0..5]&#x60; &#x3D; &#x60;reviewed_at&#x60; big-endian, &#x60;b[6] &#x3D; 0x70 | (h[0] &amp; 0x0F)&#x60;, &#x60;b[7] &#x3D; h[1]&#x60;, &#x60;b[8] &#x3D; 0x80 | (h[2] &amp; 0x3F)&#x60;, &#x60;b[9..15] &#x3D; h[3..9]&#x60;. A rating event whose id already exists in &#x60;review_log&#x60; (any state, undone included) is not emitted again (&#x60;reviews_already_imported&#x60;). Rating ops of one card are emitted in group order, after the card\&#39;s &#x60;card.created&#x60; (if any).  **&#x60;import_reviews &#x3D; false&#x60;**: nothing but word statuses is written. Rows are bucketed by the same rules (&#x60;*_carded&#x60; then means \&quot;would get a note and card\&quot;); &#x60;notes_created&#x60;, &#x60;notes_existing&#x60;, &#x60;cards_created&#x60;, &#x60;kanji_notes_*&#x60;, &#x60;kanji_cards_*&#x60; are 0; rating events count as &#x60;reviews_skipped&#x60;; status, duplicate and unknown-grade events as usual. **&#x60;import_word_status &#x3D; false&#x60;**: no &#x60;word.status_set&#x60;; bucketing is unchanged.  **Counting.** Every input record - each row of the four arrays and each element of its &#x60;reviews[]&#x60; - is counted in exactly one bucket of &#x60;JobResultJpdbHistory&#x60; (its description gives the three sums that must hold and the order in which an event\&#39;s bucket is decided). 

### Example

```ts
import {
  Configuration,
  MediaApi,
} from '@kotobaya/api-client';
import type { ImportJpdbHistoryRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MediaApi(config);

  const body = {
    // JpdbHistoryImportRequest
    jpdbHistoryImportRequest: {"blob_hash":"4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a"},
  } satisfies ImportJpdbHistoryRequest;

  try {
    const data = await api.importJpdbHistory(body);
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
| **jpdbHistoryImportRequest** | [JpdbHistoryImportRequest](JpdbHistoryImportRequest.md) |  | |

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
| **200** | An import is already queued or running. |  -  |
| **202** | Import job queued. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## importMedia

> Job importMedia(mediaImportRequest)

Import your own file(s), a URL or pasted text as media (worker job).

Enqueues a job of kind &#x60;media-import&#x60; and returns it with &#x60;202&#x60;. Files are uploaded first with &#x60;PUT /blobs/{hash}&#x60; (kind &#x60;upload&#x60;) and referenced by hash; there is no multipart variant.  - &#x60;source: file&#x60; - &#x60;files&#x60; required (1 to 500). Each file becomes one segment in array   order (an EPUB becomes one segment per chapter, in book order). Every &#x60;blob_hash&#x60; must   exist (&#x60;422&#x60; naming the element otherwise). - &#x60;source: url&#x60; - &#x60;url&#x60; required (http/https); fetched by the worker with readability   extraction; one segment. - &#x60;source: paste&#x60; - &#x60;text&#x60; required (1 to 1,000,000 characters); one segment. - New media: &#x60;title&#x60; and &#x60;kind&#x60; required. The server pre-allocates &#x60;media_id&#x60; (returned   in &#x60;job.payload.media_id&#x60;); the job creates the &#x60;media&#x60; row, its deck (&#x60;deck.id&#x60; &#x3D;   &#x60;media_id&#x60;) and all content, emitting as server ops &#x60;media.created&#x60;, &#x60;deck.created&#x60;,   &#x60;media.content_published&#x60;, &#x60;deck.content_published&#x60;. &#x60;GET /media/{media_id}&#x60; is &#x60;404&#x60;   until the job has created the row. - Existing media: pass &#x60;media_id&#x60; (must be live, with &#x60;source&#x60; equal to this request\&#39;s);   the new segments are appended after the existing ones and a new content version is   published. &#x60;title&#x60;/&#x60;kind&#x60;/&#x60;deck&#x60; must then be absent. - Dedupe key &#x60;media-import:&lt;media_id&gt;&#x60;: a second import into the same media while one is   queued or running is &#x60;409 already_exists&#x60; (&#x60;existing_id&#x60; &#x3D; the job id). - **Idempotent retry: &#x60;import_id&#x60;.** Optional client-generated UUIDv7, minted once per   user action and reused on every retry; it becomes the job\&#39;s &#x60;id&#x60;, and for new media   also the new &#x60;media_id&#x60; (and so the deck id). If a job with this id already exists   (any status), or - for new media - a &#x60;media&#x60; row with this id exists (live or deleted),   the server enqueues nothing and answers &#x60;200&#x60; with that job (or, if the job row is   gone, the most recent &#x60;media-import&#x60; job for that media; &#x60;404&#x60; if none), whatever the   rest of the body says. Without &#x60;import_id&#x60; a retry after a lost &#x60;202&#x60; imports twice   (a second media and deck); clients that may retry MUST send it. An &#x60;import_id&#x60; used by   a job of another kind is &#x60;409 already_exists&#x60; (&#x60;existing_id&#x60; &#x3D; that job). 

### Example

```ts
import {
  Configuration,
  MediaApi,
} from '@kotobaya/api-client';
import type { ImportMediaRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MediaApi(config);

  const body = {
    // MediaImportRequest
    mediaImportRequest: {"files":[{"blob_hash":"fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9","filename":"ep03.srt"}],"media_id":"01931f40-3000-7000-8000-000000000e01","source":"file"},
  } satisfies ImportMediaRequest;

  try {
    const data = await api.importMedia(body);
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
| **mediaImportRequest** | [MediaImportRequest](MediaImportRequest.md) |  | |

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
| **200** | Retry with a known &#x60;import_id&#x60; - the existing job; nothing was enqueued. |  -  |
| **202** | Import job queued. |  -  |
| **404** | No such resource. |  -  |
| **409** | A natural-key collision; &#x60;existing_id&#x60; names the row that holds the key. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listMedia

> MediaPage listMedia(kind, q, includeDeleted, limit, cursor)

List the media library.

Ordered by &#x60;created_at&#x60; descending, then &#x60;id&#x60; descending.

### Example

```ts
import {
  Configuration,
  MediaApi,
} from '@kotobaya/api-client';
import type { ListMediaRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MediaApi(config);

  const body = {
    // MediaKind | Only media of this kind. (optional)
    kind: anime,
    // string | Case-insensitive substring of the title. (optional)
    q: フリーレン,
    // boolean | Also return tombstoned rows (`deleted_at` set). (optional)
    includeDeleted: false,
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListMediaRequest;

  try {
    const data = await api.listMedia(body);
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
| **kind** | `MediaKind` | Only media of this kind. | [Optional] [Defaults to `undefined`] [Enum: anime, novel, vn, manga, web, other] |
| **q** | `string` | Case-insensitive substring of the title. | [Optional] [Defaults to `undefined`] |
| **includeDeleted** | `boolean` | Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [Optional] [Defaults to `false`] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**MediaPage**](MediaPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of media. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listMediaSentences

> SentencePage listMediaSentences(mediaId, filter, segmentId, q, entrySeq, grammarPointId, limit, cursor)

Sentences of a media, optionally only i+1 sentences.

Same as &#x60;GET /sentences&#x60; with &#x60;media_id&#x60; fixed. Ordered by segment &#x60;ordinal&#x60;, then sentence &#x60;ordinal&#x60;. jpdb-imported media have no sentences (empty page). 

### Example

```ts
import {
  Configuration,
  MediaApi,
} from '@kotobaya/api-client';
import type { ListMediaSentencesRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MediaApi(config);

  const body = {
    // string | Media id.
    mediaId: 01931f40-3000-7000-8000-000000000e01,
    // 'all' | 'i+0' | 'i+1' | `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence\'s tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`.  (optional)
    filter: i+1,
    // string | Only sentences of this segment. (optional)
    segmentId: 01931f40-3100-7000-8000-000000000e11,
    // string | Substring of the sentence text (exact characters, no normalisation). (optional)
    q: 食べ,
    // number | Only sentences with a token mapped to this entry. (optional)
    entrySeq: 1358280,
    // string | Only sentences with a live span of this grammar point. (optional)
    grammarPointId: 01931f40-4000-7000-8000-000000000901,
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListMediaSentencesRequest;

  try {
    const data = await api.listMediaSentences(body);
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
| **mediaId** | `string` | Media id. | [Defaults to `undefined`] |
| **filter** | `all`, `i+0`, `i+1` | &#x60;all&#x60; (default); &#x60;i+1&#x60; &#x3D; sentences with exactly one unknown word; &#x60;i+0&#x60; &#x3D; none. \&quot;Unknown word\&quot; &#x3D; a distinct entry among the sentence\&#39;s tokens whose effective word status is &#x60;new&#x60;; tokens of class &#x60;particle&#x60;, &#x60;auxiliary&#x60;, &#x60;name&#x60;, unmapped tokens and entries with status &#x60;blacklisted&#x60;/&#x60;ignored&#x60; are not counted. Because an unencoded &#x60;+&#x60; in a query string decodes to a space, servers MUST also accept &#x60;i 1&#x60; and &#x60;i 0&#x60;.  | [Optional] [Defaults to `undefined`] [Enum: all, i+0, i+1] |
| **segmentId** | `string` | Only sentences of this segment. | [Optional] [Defaults to `undefined`] |
| **q** | `string` | Substring of the sentence text (exact characters, no normalisation). | [Optional] [Defaults to `undefined`] |
| **entrySeq** | `number` | Only sentences with a token mapped to this entry. | [Optional] [Defaults to `undefined`] |
| **grammarPointId** | `string` | Only sentences with a live span of this grammar point. | [Optional] [Defaults to `undefined`] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**SentencePage**](SentencePage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of sentences. |  -  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listSentences

> SentencePage listSentences(mediaId, filter, segmentId, q, entrySeq, grammarPointId, limit, cursor)

Search sentences across all media.

All filters combine with AND. Ordered by media &#x60;created_at&#x60; descending, media id, segment &#x60;ordinal&#x60;, sentence &#x60;ordinal&#x60;. Sentences of deleted media do not exist any more and are never returned. LLM-generated sentences (&#x60;origin &#x3D; generated&#x60;) are included and must be labelled by clients. Each sentence carries its live grammar spans (detected and manual). 

### Example

```ts
import {
  Configuration,
  MediaApi,
} from '@kotobaya/api-client';
import type { ListSentencesRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MediaApi(config);

  const body = {
    // string | Only sentences of this media. (optional)
    mediaId: 01931f40-3000-7000-8000-000000000e01,
    // 'all' | 'i+0' | 'i+1' | `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence\'s tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`.  (optional)
    filter: i+1,
    // string | Only sentences of this segment. (optional)
    segmentId: 01931f40-3100-7000-8000-000000000e11,
    // string | Substring of the sentence text (exact characters, no normalisation). (optional)
    q: 食べ,
    // number | Only sentences with a token mapped to this entry. (optional)
    entrySeq: 1358280,
    // string | Only sentences with a live span of this grammar point. (optional)
    grammarPointId: 01931f40-4000-7000-8000-000000000901,
    // number | Page size. (optional)
    limit: 50,
    // string | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)
    cursor: eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19,
  } satisfies ListSentencesRequest;

  try {
    const data = await api.listSentences(body);
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
| **mediaId** | `string` | Only sentences of this media. | [Optional] [Defaults to `undefined`] |
| **filter** | `all`, `i+0`, `i+1` | &#x60;all&#x60; (default); &#x60;i+1&#x60; &#x3D; sentences with exactly one unknown word; &#x60;i+0&#x60; &#x3D; none. \&quot;Unknown word\&quot; &#x3D; a distinct entry among the sentence\&#39;s tokens whose effective word status is &#x60;new&#x60;; tokens of class &#x60;particle&#x60;, &#x60;auxiliary&#x60;, &#x60;name&#x60;, unmapped tokens and entries with status &#x60;blacklisted&#x60;/&#x60;ignored&#x60; are not counted. Because an unencoded &#x60;+&#x60; in a query string decodes to a space, servers MUST also accept &#x60;i 1&#x60; and &#x60;i 0&#x60;.  | [Optional] [Defaults to `undefined`] [Enum: all, i+0, i+1] |
| **segmentId** | `string` | Only sentences of this segment. | [Optional] [Defaults to `undefined`] |
| **q** | `string` | Substring of the sentence text (exact characters, no normalisation). | [Optional] [Defaults to `undefined`] |
| **entrySeq** | `number` | Only sentences with a token mapped to this entry. | [Optional] [Defaults to `undefined`] |
| **grammarPointId** | `string` | Only sentences with a live span of this grammar point. | [Optional] [Defaults to `undefined`] |
| **limit** | `number` | Page size. | [Optional] [Defaults to `50`] |
| **cursor** | `string` | Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [Optional] [Defaults to `undefined`] |

### Return type

[**SentencePage**](SentencePage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | One page of sentences. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## reparseMedia

> Job reparseMedia(mediaId)

Re-parse a media (after parse overrides or a dictionary refresh).

Enqueues a job of kind &#x60;media-parse&#x60; (dedupe key &#x60;media-parse:&lt;media_id&gt;&#x60;); an equivalent queued or running job is returned with &#x60;200&#x60;. The job keeps segment and sentence ids as SCHEMA.md requires and makes the server emit &#x60;media.content_published&#x60; and &#x60;deck.content_published&#x60;. Media with &#x60;source &#x3D; jpdb&#x60; has nothing to parse: &#x60;409 not_applicable&#x60;. 

### Example

```ts
import {
  Configuration,
  MediaApi,
} from '@kotobaya/api-client';
import type { ReparseMediaRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MediaApi(config);

  const body = {
    // string | Media id.
    mediaId: 01931f40-3000-7000-8000-000000000e01,
  } satisfies ReparseMediaRequest;

  try {
    const data = await api.reparseMedia(body);
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
| **mediaId** | `string` | Media id. | [Defaults to `undefined`] |

### Return type

[**Job**](Job.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | A parse is already queued or running. |  -  |
| **202** | Parse job queued. |  -  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateMedia

> Media updateMedia(mediaId, mediaPatch)

Change a media\&#39;s title, kind or cover.

Server emits one &#x60;media.updated&#x60; with the registers present (&#x60;title&#x60;, &#x60;kind&#x60;, &#x60;cover_blob_hash&#x60;). &#x60;cover_blob_hash&#x60; must be an existing blob of kind &#x60;image&#x60;, or &#x60;null&#x60; to remove the cover. The media deck\&#39;s name is not changed (patch the deck). Empty body: &#x60;422&#x60;. 

### Example

```ts
import {
  Configuration,
  MediaApi,
} from '@kotobaya/api-client';
import type { UpdateMediaRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new MediaApi(config);

  const body = {
    // string | Media id.
    mediaId: 01931f40-3000-7000-8000-000000000e01,
    // MediaPatch
    mediaPatch: {"kind":"anime","title":"葬送のフリーレン (S1)"},
  } satisfies UpdateMediaRequest;

  try {
    const data = await api.updateMedia(body);
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
| **mediaId** | `string` | Media id. | [Defaults to `undefined`] |
| **mediaPatch** | [MediaPatch](MediaPatch.md) |  | |

### Return type

[**Media**](Media.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The updated media. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

